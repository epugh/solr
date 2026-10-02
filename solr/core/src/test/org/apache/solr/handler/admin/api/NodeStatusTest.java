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
package org.apache.solr.handler.admin.api;

import org.apache.solr.client.api.model.NodeStatusResponse;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.request.ClusterApi;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrException;
import org.apache.solr.embedded.JettySolrRunner;
import org.junit.BeforeClass;
import org.junit.Test;

/** HTTP tests for {@code GET /api/cluster/nodes/{nodeName}} via the generated SolrJ ClusterApi. */
public class NodeStatusTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(2).addConfig("conf", configset("cloud-minimal")).configure();
  }

  @Test
  public void testNodeStatus() throws Exception {
    final String collection = "nodeStatusTest";
    CollectionAdminRequest.createCollection(collection, "conf", 1, 2)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(collection, 1, 2);

    final JettySolrRunner jetty = cluster.getJettySolrRunners().get(0);
    final String nodeName = jetty.getNodeName();

    final NodeStatusResponse rsp =
        new ClusterApi.GetNodeStatus(nodeName).process(cluster.getSolrClient());

    assertNotNull(rsp);
    assertNull(rsp.error);
    assertEquals(nodeName, rsp.nodeName);
    assertTrue(rsp.live);
    assertNotNull(rsp.replicas);
    assertTrue(rsp.replicas > 0);
    assertNotNull(rsp.collections);
    assertTrue(rsp.collections.containsKey(collection));

    final var shardCounts = rsp.collections.get(collection).get("shard1");
    assertNotNull(shardCounts);
    assertEquals(Integer.valueOf(1), shardCounts.totalReplicas);
    assertEquals(Integer.valueOf(1), shardCounts.activeReplicas);
  }

  @Test
  public void testUnknownNodeReturns404() {
    final RemoteSolrException ex =
        expectThrows(
            RemoteSolrException.class,
            () -> new ClusterApi.GetNodeStatus("bogus:1234_solr").process(cluster.getSolrClient()));
    assertEquals(SolrException.ErrorCode.NOT_FOUND.code, ex.code());
  }
}
