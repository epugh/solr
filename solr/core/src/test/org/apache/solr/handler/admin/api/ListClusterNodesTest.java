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

import java.util.Set;
import java.util.stream.Collectors;
import org.apache.solr.client.api.model.ListClusterNodesResponse;
import org.apache.solr.client.solrj.request.ClusterApi;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.embedded.JettySolrRunner;
import org.junit.BeforeClass;
import org.junit.Test;

/** HTTP tests for {@code GET /api/cluster/nodes} via the generated SolrJ ClusterApi client. */
public class ListClusterNodesTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(2).addConfig("conf", configset("cloud-minimal")).configure();
  }

  @Test
  public void testListLiveNodes() throws Exception {
    ListClusterNodesResponse rsp =
        new ClusterApi.ListClusterNodes().process(cluster.getSolrClient());

    assertNotNull(rsp);
    assertNull(rsp.error);
    assertNotNull(rsp.nodes);
    assertNull(rsp.nodesDetail);

    Set<String> expected =
        cluster.getJettySolrRunners().stream()
            .map(JettySolrRunner::getNodeName)
            .collect(Collectors.toSet());
    assertEquals(expected, rsp.nodes);
  }

  @Test
  public void testListLiveNodesDetailed() throws Exception {
    final String collection = "listClusterNodesDetailedTest";
    CollectionAdminRequest.createCollection(collection, "conf", 1, 2)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(collection, 1, 2);

    final var request = new ClusterApi.ListClusterNodes();
    request.setDetailed(true);
    final ListClusterNodesResponse rsp = request.process(cluster.getSolrClient());

    assertNotNull(rsp);
    assertNull(rsp.error);
    assertNull(rsp.nodes);
    assertNotNull(rsp.nodesDetail);

    final Set<String> expected =
        cluster.getJettySolrRunners().stream()
            .map(JettySolrRunner::getNodeName)
            .collect(Collectors.toSet());
    assertEquals(expected, rsp.nodesDetail.keySet());

    int totalReplicasAcrossNodes = 0;
    for (var nodeState : rsp.nodesDetail.values()) {
      assertTrue(nodeState.live);
      assertNotNull(nodeState.replicas);
      totalReplicasAcrossNodes += nodeState.replicas;
    }
    assertEquals(2, totalReplicasAcrossNodes);
  }
}
