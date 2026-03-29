/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cli;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.cloud.ClusterProperties;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.util.ZLibCompressor;
import org.apache.zookeeper.CreateMode;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for ZooKeeper CLI subcommands (cp, ls, rm, cluster property, etc.).
 *
 * <p>ConfigSet upload/download tests live in {@link ConfigSetUploadToolTest} and {@link
 * ConfigSetDownloadToolTest}. The ACL update test lives in {@link UpdateACLToolTest}.
 */
public class ZkSubcommandsTest extends SolrCloudTestCase {

  protected static final Path SOLR_HOME = TEST_HOME();

  private static String zkAddr;
  private static SolrZkClient zkClient;

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(1)
        .addConfig(
            "conf1", TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .configure();
    zkAddr = cluster.getZkServer().getZkAddress();
    zkClient =
        new SolrZkClient.Builder()
            .withUrl(zkAddr)
            .withTimeout(30000, TimeUnit.MILLISECONDS)
            .build();
    // Set zkHost so tools that fall back to it (e.g. cp without -z) work.
    System.setProperty("zkHost", zkAddr);
    // Set solr.home so the ZkCpTool can load solr.xml, which reads compression settings
    // from system properties like minStateByteLenForCompression.
    System.setProperty("solr.home", TEST_HOME().toString());
    System.setProperty("solr.solr.home", TEST_HOME().toString());
  }

  @AfterClass
  public static void closeConn() {
    if (null != zkClient) {
      zkClient.close();
      zkClient = null;
    }
    zkAddr = null;
  }

  @Test
  public void testPut() throws Exception {
    // test put
    String data = "my data";
    Path localFile = Files.createTempFile("temp", ".data");
    BufferedWriter writer = Files.newBufferedWriter(localFile, StandardCharsets.UTF_8);
    writer.write(data);
    writer.close();

    String[] args = new String[] {"cp", "-z", zkAddr, localFile.toString(), "zk:/data.txt"};

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    assertArrayEquals(
        zkClient.getData("/data.txt", null, null), data.getBytes(StandardCharsets.UTF_8));

    // test re-put to existing
    data = "my data deux";

    // Write text to the temporary file
    writer = Files.newBufferedWriter(localFile, StandardCharsets.UTF_8);
    writer.write(data);
    writer.close();

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    assertArrayEquals(
        zkClient.getData("/data.txt", null, null), data.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void testPutCompressed() throws Exception {
    // test put compressed; each test has its own ZK path to avoid conflicts in the shared cluster.
    System.setProperty("minStateByteLenForCompression", "0");

    String data = "my data";

    Path localFile = Files.createTempFile("state", ".json");
    BufferedWriter writer = Files.newBufferedWriter(localFile, StandardCharsets.UTF_8);
    writer.write(data);
    writer.close();

    ZLibCompressor zLibCompressor = new ZLibCompressor();
    byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
    byte[] expected = zLibCompressor.compressBytes(dataBytes);

    String[] args =
        new String[] {"cp", "-z", zkAddr, localFile.toString(), "zk:/statePutCompressed.json"};

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    assertArrayEquals(
        dataBytes, zkClient.getCuratorFramework().getData().forPath("/statePutCompressed.json"));
    assertArrayEquals(
        expected,
        zkClient
            .getCuratorFramework()
            .getData()
            .undecompressed()
            .forPath("/statePutCompressed.json"));

    // test re-put to existing
    data = "my data deux";
    localFile = Files.createTempFile("state", ".json");
    writer = Files.newBufferedWriter(localFile, StandardCharsets.UTF_8);
    writer.write(data);
    writer.close();

    dataBytes = data.getBytes(StandardCharsets.UTF_8);
    expected = zLibCompressor.compressBytes(dataBytes);

    byte[] fromLocal = new ZLibCompressor().compressBytes(Files.readAllBytes(localFile));
    assertArrayEquals("Should get back what we put in ZK", fromLocal, expected);

    args = new String[] {"cp", "-z", zkAddr, localFile.toString(), "zk:/statePutCompressed.json"};
    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    byte[] fromZkRaw =
        zkClient
            .getCuratorFramework()
            .getData()
            .undecompressed()
            .forPath("/statePutCompressed.json");
    byte[] fromZk = zkClient.getCuratorFramework().getData().forPath("/statePutCompressed.json");
    byte[] fromLocRaw = Files.readAllBytes(localFile);
    byte[] fromLoc = new ZLibCompressor().compressBytes(fromLocRaw);
    assertArrayEquals(
        "When asking to not decompress, we should get back the compressed data that what we put in ZK",
        fromLoc,
        fromZkRaw);
    assertArrayEquals(
        "When not specifying anything, we should get back what exactly we put in ZK (not compressed)",
        fromLocRaw,
        fromZk);

    assertArrayEquals(
        dataBytes, zkClient.getCuratorFramework().getData().forPath("/statePutCompressed.json"));
    assertArrayEquals(
        expected,
        zkClient
            .getCuratorFramework()
            .getData()
            .undecompressed()
            .forPath("/statePutCompressed.json"));
  }

  @Test
  public void testPutFile() throws Exception {

    String[] args =
        new String[] {
          "cp", "-z", zkAddr, SOLR_HOME.resolve("solr-stress-new.xml").toString(), "zk:/fooFile.xml"
        };

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    String fromZk =
        new String(zkClient.getData("/fooFile.xml", null, null), StandardCharsets.UTF_8);
    Path localFile = SOLR_HOME.resolve("solr-stress-new.xml");
    String fromLocalFile = Files.readString(localFile);
    assertEquals("Should get back what we put in ZK", fromZk, fromLocalFile);
  }

  @Test
  public void testPutFileWithoutSlash() throws Exception {

    String[] args =
        new String[] {
          "cp",
          "-z",
          zkAddr,
          SOLR_HOME.resolve("solr-stress-new.xml").toString(),
          "zk:fooNoSlash.xml"
        };

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    String fromZk =
        new String(zkClient.getData("/fooNoSlash.xml", null, null), StandardCharsets.UTF_8);
    Path localFile = SOLR_HOME.resolve("solr-stress-new.xml");
    String fromLocalFile = Files.readString(localFile);
    assertEquals("Should get back what we put in ZK", fromZk, fromLocalFile);
  }

  @Test
  public void testPutFileCompressed() throws Exception {
    // test put file compressed
    System.setProperty("minStateByteLenForCompression", "0");

    String[] args =
        new String[] {
          "cp",
          "-z",
          zkAddr,
          SOLR_HOME.resolve("solr-stress-new.xml").toString(),
          "zk:/statePutFileCompressed.json"
        };

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    Path locFile = SOLR_HOME.resolve("solr-stress-new.xml");
    byte[] fileBytes = Files.readAllBytes(locFile);

    // Check raw ZK data
    byte[] fromZk =
        zkClient
            .getCuratorFramework()
            .getData()
            .undecompressed()
            .forPath("/statePutFileCompressed.json");
    byte[] fromLoc = new ZLibCompressor().compressBytes(fileBytes);
    assertArrayEquals("Should get back a compressed version of what we put in ZK", fromLoc, fromZk);

    // Check curator output (should be decompressed)
    fromZk = zkClient.getCuratorFramework().getData().forPath("/statePutFileCompressed.json");
    assertArrayEquals(
        "Should get back an uncompressed version what we put in ZK", fileBytes, fromZk);

    // Let's do it again
    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    locFile = SOLR_HOME.resolve("solr-stress-new.xml");
    fileBytes = Files.readAllBytes(locFile);

    fromZk =
        zkClient
            .getCuratorFramework()
            .getData()
            .undecompressed()
            .forPath("/statePutFileCompressed.json");
    fromLoc = new ZLibCompressor().compressBytes(fileBytes);
    assertArrayEquals("Should get back a compressed version of what we put in ZK", fromLoc, fromZk);

    // Check curator output (should be decompressed)
    fromZk = zkClient.getCuratorFramework().getData().forPath("/statePutFileCompressed.json");
    assertArrayEquals(
        "Should get back an uncompressed version what we put in ZK", fileBytes, fromZk);
  }

  @Test
  public void testPutFileNotExists() throws Exception {

    String[] args =
        new String[] {
          "cp", "-z", zkAddr, SOLR_HOME.resolve("not-there.xml").toString(), "zk:/fooNotExists.xml"
        };

    assertEquals(1, CLITestHelper.runTool(args, ZkCpTool.class));
  }

  @Test
  public void testLs() throws Exception {
    // Use a unique path to avoid interference with cluster ZK data.
    zkClient.makePath("/zkSubcmdsTest/path", true);

    // Test that the path arg does not have to be the last argument.
    String[] args = new String[] {"ls", "/zkSubcmdsTest", "-r", "true", "-z", zkAddr};

    CLITestHelper.TestingRuntime runtime = new CLITestHelper.TestingRuntime(true);
    assertEquals(0, CLITestHelper.runTool(args, runtime, ZkLsTool.class));

    final String output = runtime.getOutput();
    String sep = System.lineSeparator();
    // Indentation equals the position of the last '/' in the full child path.
    String indent = " ".repeat("/zkSubcmdsTest".length());
    assertEquals("/zkSubcmdsTest" + sep + indent + "path" + sep, output);
  }

  @Test
  public void testGet() throws Exception {
    String getNode = "/getNode";
    byte[] data = "getNode-data".getBytes(StandardCharsets.UTF_8);
    zkClient.create(getNode, data, CreateMode.PERSISTENT);

    Path localFile = Files.createTempFile("temp", ".data");

    String[] args = new String[] {"cp", "-z", zkAddr, "zk:" + getNode, localFile.toString()};

    CLITestHelper.TestingRuntime runtime = new CLITestHelper.TestingRuntime(true);
    assertEquals(0, CLITestHelper.runTool(args, runtime, ZkCpTool.class));

    final String standardOutput2 = runtime.getOutput();
    assertTrue(standardOutput2.startsWith("Copying from 'zk:/getNode'"));
    byte[] fileBytes = Files.readAllBytes(localFile);
    assertArrayEquals(data, fileBytes);
  }

  @Test
  public void testGetCompressed() throws Exception {
    System.setProperty("minStateByteLenForCompression", "0");

    String getNode = "/getNodeCompressed";
    byte[] data = "getNode-data".getBytes(StandardCharsets.UTF_8);
    ZLibCompressor zLibCompressor = new ZLibCompressor();
    byte[] compressedData =
        random().nextBoolean()
            ? zLibCompressor.compressBytes(data)
            : zLibCompressor.compressBytes(data, data.length / 10);
    zkClient.create(getNode, compressedData, CreateMode.PERSISTENT);

    Path localFile = Files.createTempFile("temp", ".data");

    String[] args = new String[] {"cp", "-z", zkAddr, "zk:" + getNode, localFile.toString()};

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    assertArrayEquals(data, Files.readAllBytes(localFile));
  }

  @Test
  public void testGetFile() throws Exception {
    Path tmpDir = createTempDir();

    String getNode = "/getFileNode";
    byte[] data = "getFileNode-data".getBytes(StandardCharsets.UTF_8);
    zkClient.create(getNode, data, CreateMode.PERSISTENT);

    Path file =
        tmpDir.resolve("solrtest-getfile-" + this.getClass().getName() + "-" + System.nanoTime());

    // Not setting --zk-host, will fall back to sysProp 'zkHost'
    String[] args = new String[] {"cp", "zk:" + getNode, file.toString()};

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));
    assertArrayEquals(data, Files.readAllBytes(file));
  }

  @Test
  public void testGetFileCompressed() throws Exception {
    Path tmpDir = createTempDir();

    String getNode = "/getFileNodeCompressed";
    byte[] data = "getFileNode-data".getBytes(StandardCharsets.UTF_8);
    ZLibCompressor zLibCompressor = new ZLibCompressor();
    byte[] compressedData =
        random().nextBoolean()
            ? zLibCompressor.compressBytes(data)
            : zLibCompressor.compressBytes(data, data.length / 10);
    zkClient.create(getNode, compressedData, CreateMode.PERSISTENT);

    Path file =
        tmpDir.resolve("solrtest-getfile-" + this.getClass().getName() + "-" + System.nanoTime());

    String[] args = new String[] {"cp", "-z", zkAddr, "zk:" + getNode, file.toString()};

    assertEquals(0, CLITestHelper.runTool(args, ZkCpTool.class));

    assertArrayEquals(data, Files.readAllBytes(file));
  }

  @Test
  public void testGetFileNotExists() throws Exception {
    String getNode = "/getFileNotExistsNode";

    Path file = createTempFile("newfile", null);

    String[] args = new String[] {"cp", "-z", zkAddr, "zk:" + getNode, file.toString()};

    assertEquals(1, CLITestHelper.runTool(args, ZkCpTool.class));
  }

  @Test
  public void testInvalidZKAddress() throws Exception {

    String[] args = new String[] {"ls", "/", "-r", "-z", "----------:33332"};

    assertEquals(1, CLITestHelper.runTool(args, ZkLsTool.class));
  }

  @Test
  public void testSetClusterProperty() throws Exception {
    ClusterProperties properties = new ClusterProperties(zkClient);
    // add property urlScheme=http
    String[] args =
        new String[] {"cluster", "--property", "urlScheme", "--value", "http", "-z", zkAddr};
    assertEquals(0, CLITestHelper.runTool(args, ClusterTool.class));

    assertEquals("http", properties.getClusterProperty("urlScheme", "none"));

    args = new String[] {"cluster", "--property", "urlScheme", "-z", zkAddr};
    assertEquals(0, CLITestHelper.runTool(args, ClusterTool.class));
    assertNull(properties.getClusterProperty("urlScheme", (String) null));
  }
}
