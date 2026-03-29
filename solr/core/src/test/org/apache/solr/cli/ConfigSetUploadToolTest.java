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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.cloud.SolrZkClient;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Tests for {@link ConfigSetUploadTool} using the Solr HTTP V2 API via {@code -s <solr-url>}. */
public class ConfigSetUploadToolTest extends SolrCloudTestCase {

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
  public void testUpconfig() throws Exception {
    String solrUrl = cluster.getJettySolrRunner(0).getBaseUrl().toString();
    Path configSet = TEST_PATH().resolve("configsets");
    Path confDir = configSet.resolve("cloud-subdirs");

    String[] args =
        new String[] {
          "upconfig", "--conf-name", "upconfig1", "--conf-dir", confDir.toString(), "-s", solrUrl
        };

    assertEquals(
        "upconfig should succeed", 0, CLITestHelper.runTool(args, ConfigSetUploadTool.class));

    // Verify the config was uploaded to ZK (getConfigsetPath auto-navigates into conf/)
    assertTrue("Config should exist in ZK", zkClient.exists("/configs/upconfig1/schema.xml"));

    String content =
        new String(
            zkClient.getData("/configs/upconfig1/schema.xml", null, null), StandardCharsets.UTF_8);
    assertTrue(
        "There should be content in the node!", content.contains("Apache Software Foundation"));
  }

  @Test
  public void testUpconfigWithZkHost() throws Exception {
    // Verify that -z (zk-host) also works: the tool resolves a live Solr node from ZK.
    Path configSet = TEST_PATH().resolve("configsets");
    Path confDir = configSet.resolve("cloud-subdirs");

    String[] args =
        new String[] {
          "upconfig", "--conf-name", "upconfig-zk", "--conf-dir", confDir.toString(), "-z", zkAddr
        };

    assertEquals(
        "upconfig via -z should succeed",
        0,
        CLITestHelper.runTool(args, ConfigSetUploadTool.class));

    assertTrue(
        "Config should exist in ZK after upload via -z",
        zkClient.exists("/configs/upconfig-zk/schema.xml"));
  }

  @Test
  public void testUpconfigBadPath() throws Exception {
    String solrUrl = cluster.getJettySolrRunner(0).getBaseUrl().toString();

    String[] args =
        new String[] {
          "upconfig", "--conf-name", "upconfig-bad", "--conf-dir", "nonexistentpath", "-s", solrUrl
        };

    assertNotEquals(
        "upconfig should fail with a non-existent path",
        0,
        CLITestHelper.runTool(args, ConfigSetUploadTool.class));
  }
}
