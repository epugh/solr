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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkMaintenanceUtils;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Tests for {@link ConfigSetDownloadTool}. */
public class ConfigSetDownloadToolTest extends SolrCloudTestCase {

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
  public void testDownconfig() throws Exception {
    String solrUrl = cluster.getJettySolrRunner(0).getBaseUrl().toString();
    Path configSet = TEST_PATH().resolve("configsets");
    Path srcConfDir = configSet.resolve("cloud-subdirs").resolve("conf");

    // First upload via the HTTP API tool
    String[] args =
        new String[] {
          "upconfig",
          "--conf-name",
          "downconfig1",
          "--conf-dir",
          configSet.resolve("cloud-subdirs").toString(),
          "-s",
          solrUrl
        };
    assertEquals(
        "upconfig should succeed", 0, CLITestHelper.runTool(args, ConfigSetUploadTool.class));

    // Now download via the ZK-based tool
    Path tmp = createTempDir("downConfigToolTest");
    args =
        new String[] {
          "downconfig", "--conf-name", "downconfig1", "--conf-dir", tmp.toString(), "-z", zkAddr
        };

    assertEquals(
        "downconfig should succeed", 0, CLITestHelper.runTool(args, ConfigSetDownloadTool.class));

    // Verify all uploaded files are present locally.
    // The download tool writes files to a "conf" subdir, and the ZK root lacks the "conf/" prefix
    // because getConfigsetPath() navigates into the conf/ dir automatically during upload.
    verifyZkLocalPathsMatch(srcConfDir, "/configs/downconfig1");
  }

  @Test
  public void testDownconfigEmptyFile() throws Exception {
    String solrUrl = cluster.getJettySolrRunner(0).getBaseUrl().toString();
    Path configSet = TEST_PATH().resolve("configsets");

    // Upload the configset
    String[] args =
        new String[] {
          "upconfig",
          "--conf-name",
          "downconfig2",
          "--conf-dir",
          configSet.resolve("cloud-subdirs").toString(),
          "-s",
          solrUrl
        };
    assertEquals(
        "upconfig should succeed", 0, CLITestHelper.runTool(args, ConfigSetUploadTool.class));

    // Download it
    Path tmp = createTempDir("downConfigEmptyTest").resolve("myconfset");
    args =
        new String[] {
          "downconfig", "--conf-name", "downconfig2", "--conf-dir", tmp.toString(), "-z", zkAddr
        };
    assertEquals(
        "downconfig should succeed", 0, CLITestHelper.runTool(args, ConfigSetDownloadTool.class));

    // Create an empty file in the downloaded config
    Path emptyFile = tmp.resolve("conf").resolve("stopwords").resolve("emptyfile");
    Files.createFile(emptyFile);

    // Upload it again (with the empty file included via the ZK-compatible copyConfigUp helper)
    AbstractFullDistribZkTestBase.copyConfigUp(
        tmp.getParent(), "myconfset", "downconfig2b", zkAddr);

    // Download back
    Path tmp2 = createTempDir("downConfigEmptyTest2");
    args =
        new String[] {
          "downconfig", "--conf-name", "downconfig2b", "--conf-dir", tmp2.toString(), "-z", zkAddr
        };
    assertEquals(
        "downconfig should succeed", 0, CLITestHelper.runTool(args, ConfigSetDownloadTool.class));

    Path destEmpty = tmp2.resolve("conf").resolve("stopwords").resolve("emptyfile");
    assertTrue(
        "Empty files should NOT be copied down as directories", Files.isRegularFile(destEmpty));
  }

  private void verifyZkLocalPathsMatch(Path fileRoot, String zkRoot)
      throws IOException, KeeperException, InterruptedException {
    verifyAllFilesAreZNodes(fileRoot, zkRoot);
    verifyAllZNodesAreFiles(fileRoot, zkRoot);
  }

  private static boolean isEphemeral(String zkPath) throws KeeperException, InterruptedException {
    Stat znodeStat = zkClient.exists(zkPath, null);
    return znodeStat.getEphemeralOwner() != 0;
  }

  private void verifyAllZNodesAreFiles(Path fileRoot, String zkRoot)
      throws KeeperException, InterruptedException {
    for (String child : zkClient.getChildren(zkRoot, null)) {
      if (!zkRoot.endsWith("/")) {
        zkRoot += "/";
      }
      if (isEphemeral(zkRoot + child)) continue;

      Path thisPath = fileRoot.resolve(child);
      assertTrue(
          "Znode " + child + " should have been found on disk at " + fileRoot,
          Files.exists(thisPath));
      verifyAllZNodesAreFiles(thisPath, zkRoot + child);
    }
  }

  private void verifyAllFilesAreZNodes(Path fileRoot, String zkRoot) throws IOException {
    Files.walkFileTree(
        fileRoot,
        new SimpleFileVisitor<Path>() {
          void checkPathOnZk(Path path) {
            String znode = ZkMaintenanceUtils.createZkNodeName(zkRoot, fileRoot, path);
            try {
              assertTrue("Should have found " + znode + " on Zookeeper", zkClient.exists(znode));
            } catch (Exception e) {
              fail(
                  "Caught unexpected exception "
                      + e.getMessage()
                      + " Znode we were checking "
                      + znode);
            }
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            assertTrue("Path should start at proper place!", file.startsWith(fileRoot));
            checkPathOnZk(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            checkPathOnZk(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
