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

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.cloud.AbstractZkTestCase;
import org.apache.solr.cloud.ZkTestServer;
import org.apache.solr.common.cloud.DigestZkACLProvider;
import org.apache.solr.common.cloud.DigestZkCredentialsProvider;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.VMParamsZkCredentialsInjector;
import org.apache.zookeeper.KeeperException;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link UpdateACLTool}. This test modifies ZooKeeper ACLs on the root path, which is
 * destructive to a shared cluster's ZooKeeper, so it uses a standalone {@link ZkTestServer}.
 */
public class UpdateACLToolTest extends SolrTestCaseJ4 {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Test
  public void testUpdateAcls() throws Exception {
    Path zkDir = createTempDir().resolve("zookeeper/server1/data");
    ZkTestServer zkServer = new ZkTestServer(zkDir);
    try {
      zkServer.run();
      String zkAddr = zkServer.getZkAddress();

      try (SolrZkClient zkClient =
          new SolrZkClient.Builder()
              .withUrl(zkAddr)
              .withTimeout(AbstractZkTestCase.TIMEOUT, TimeUnit.MILLISECONDS)
              .build()) {
        zkClient.makePath("/solr", false);
      }

      System.setProperty(
          SolrZkClient.ZK_CRED_PROVIDER_CLASS_NAME_VM_PARAM_NAME,
          DigestZkCredentialsProvider.class.getName());
      System.setProperty(
          SolrZkClient.ZK_ACL_PROVIDER_CLASS_NAME_VM_PARAM_NAME,
          DigestZkACLProvider.class.getName());
      System.setProperty(
          SolrZkClient.ZK_CREDENTIALS_INJECTOR_CLASS_NAME_VM_PARAM_NAME,
          VMParamsZkCredentialsInjector.class.getName());
      System.setProperty(
          VMParamsZkCredentialsInjector.DEFAULT_DIGEST_READONLY_USERNAME_VM_PARAM_NAME, "user");
      System.setProperty(
          VMParamsZkCredentialsInjector.DEFAULT_DIGEST_READONLY_PASSWORD_VM_PARAM_NAME, "pass");

      String[] args = new String[] {"updateacls", "/", "-z", zkAddr};
      assertEquals(0, CLITestHelper.runTool(args, UpdateACLTool.class));

      boolean excepted = false;
      try (SolrZkClient zkClient =
          new SolrZkClient.Builder()
              .withUrl(zkAddr)
              .withTimeout(
                  AbstractFullDistribZkTestBase.DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
              .build()) {
        zkClient.getData("/", null, null);
      } catch (KeeperException.NoAuthException e) {
        excepted = true;
      }
      assertTrue("Did not fail to read.", excepted);
    } finally {
      zkServer.shutdown();
    }
  }
}
