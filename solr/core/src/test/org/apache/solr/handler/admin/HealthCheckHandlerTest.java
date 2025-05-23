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

package org.apache.solr.handler.admin;

import static org.apache.solr.common.params.CommonParams.HEALTH_CHECK_HANDLER_PATH;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.HealthCheckRequest;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.HealthCheckResponse;
import org.apache.solr.client.solrj.response.V2Response;
import org.apache.solr.cloud.CloudDescriptor;
import org.apache.solr.cloud.ClusterStateMockUtil;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.embedded.JettySolrRunner;
import org.junit.BeforeClass;
import org.junit.Test;

public class HealthCheckHandlerTest extends SolrCloudTestCase {
  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(1).addConfig("conf", configset("cloud-minimal")).configure();
  }

  private HealthCheckResponse runHealthcheckWithClient(SolrClient client) throws Exception {
    return new HealthCheckRequest().process(client);
  }

  @Test
  public void testHealthCheckHandler() throws Exception {

    // positive check that our only existing "healthy" node works with cloud client
    // NOTE: this is using GenericSolrRequest, not HealthCheckRequest which is why it passes
    // as compared with testHealthCheckHandlerWithCloudClient
    // (Not sure if that's actually a good thing -- but it's how the existing test worked)
    final var genericHealthcheck =
        new GenericSolrRequest(
            SolrRequest.METHOD.GET, HEALTH_CHECK_HANDLER_PATH, SolrRequest.SolrRequestType.ADMIN);
    assertEquals(
        CommonParams.OK,
        genericHealthcheck.process(cluster.getSolrClient()).getResponse().get(CommonParams.STATUS));

    // positive check that our exiting "healthy" node works with direct http client
    try (SolrClient solrClient =
        getHttpSolrClient(cluster.getJettySolrRunner(0).getBaseUrl().toString())) {
      assertEquals(CommonParams.OK, runHealthcheckWithClient(solrClient).getNodeStatus());
    }

    // successfully create a dummy collection
    try (SolrClient solrClient =
        getHttpSolrClient(cluster.getJettySolrRunner(0).getBaseUrl().toString())) {
      CollectionAdminResponse collectionAdminResponse =
          CollectionAdminRequest.createCollection("test", "_default", 1, 1)
              .withProperty("solr.directoryFactory", "solr.StandardDirectoryFactory")
              .process(solrClient);
      assertEquals(0, collectionAdminResponse.getStatus());
      assertEquals(CommonParams.OK, runHealthcheckWithClient(solrClient).getNodeStatus());
    } finally {
      cluster.deleteAllCollections();
      cluster.deleteAllConfigSets();
    }

    // add a new node for the purpose of negative testing
    JettySolrRunner newJetty = cluster.startJettySolrRunner();
    try (SolrClient solrClient = getHttpSolrClient(newJetty.getBaseUrl().toString())) {

      // positive check that our (new) "healthy" node works with direct http client
      final var response = runHealthcheckWithClient(solrClient);
      assertEquals(CommonParams.OK, response.getNodeStatus());

      // now "break" our (new) node
      newJetty.getCoreContainer().getZkController().getZkClient().close();

      // negative check of our (new) "broken" node that we deliberately put into an unhealthy state
      SolrClient.RemoteSolrException e =
          expectThrows(
              SolrClient.RemoteSolrException.class,
              () -> {
                runHealthcheckWithClient(solrClient);
              });
      assertTrue(e.getMessage(), e.getMessage().contains("Host Unavailable"));
      assertEquals(SolrException.ErrorCode.SERVICE_UNAVAILABLE.code, e.code());
    } finally {
      newJetty.stop();
    }

    // (redundant) positive check that our (previously) exiting "healthy" node (still) works
    // after getting negative results from our broken node and failed core container
    try (SolrClient solrClient =
        getHttpSolrClient(cluster.getJettySolrRunner(0).getBaseUrl().toString())) {

      assertEquals(CommonParams.OK, runHealthcheckWithClient(solrClient).getNodeStatus());
    }
  }

  @Test
  public void testHealthCheckHandlerSolrJ() throws IOException, SolrServerException {
    // positive check of a HealthCheckRequest using http client
    HealthCheckRequest req = new HealthCheckRequest();
    try (SolrClient solrClient =
        getHttpSolrClient(cluster.getJettySolrRunner(0).getBaseUrl().toString())) {
      HealthCheckResponse rsp = req.process(solrClient);
      assertEquals(CommonParams.OK, rsp.getNodeStatus());
    }
  }

  @Test
  public void testHealthCheckV2Api() throws Exception {
    V2Response res = new V2Request.Builder("/node/health").build().process(cluster.getSolrClient());
    assertEquals(0, res.getStatus());
    assertEquals(CommonParams.OK, res.getResponse().get(CommonParams.STATUS));

    // add a new node for the purpose of negative testing
    JettySolrRunner newJetty = cluster.startJettySolrRunner();
    try (SolrClient solrClient = getHttpSolrClient(newJetty.getBaseUrl().toString())) {

      // positive check that our (new) "healthy" node works with direct http client
      assertEquals(
          CommonParams.OK,
          new V2Request.Builder("/node/health")
              .build()
              .process(solrClient)
              .getResponse()
              .get(CommonParams.STATUS));

      // now "break" our (new) node
      newJetty.getCoreContainer().getZkController().getZkClient().close();

      // negative check of our (new) "broken" node that we deliberately put into an unhealthy state
      SolrClient.RemoteSolrException e =
          expectThrows(
              SolrClient.RemoteSolrException.class,
              () -> {
                new V2Request.Builder("/node/health").build().process(solrClient);
              });
      assertTrue(e.getMessage(), e.getMessage().contains("Host Unavailable"));
      assertEquals(SolrException.ErrorCode.SERVICE_UNAVAILABLE.code, e.code());
    } finally {
      newJetty.stop();
    }
  }

  @Test
  public void testFindUnhealthyCores() {
    // Simulate two nodes, with two collections:
    //  node1: collection1 -> shard1: [ replica1 (active), replica3 (down) ]
    //         collection2 -> shard1: [ replica2 (recovering) ]
    //  node2: collection1 -> shard1: [ replica2 (active), replica4 (down) ]
    //         collection2 -> shard1: [ replica1 (active) ]
    try (ZkStateReader reader =
        ClusterStateMockUtil.buildClusterState(
            "csrr2rDr2Dcsr2FrR", 1, "baseUrl1:8983_", "baseUrl2:8984_")) {
      ClusterState clusterState = reader.getClusterState();

      // Node 1
      Collection<CloudDescriptor> node1Cores =
          Arrays.asList(
              mockCD("collection1", "slice1_replica1", "slice1", true, Replica.State.ACTIVE),
              mockCD("collection1", "slice1_replica3", "slice1", true, Replica.State.DOWN),
              mockCD("collection2", "slice1_replica5", "slice1", true, Replica.State.RECOVERING),
              // A dangling core for a non-existent collection will not fail the check
              mockCD("invalid", "invalid", "slice1", false, Replica.State.RECOVERING),
              // A core for a slice that is not an active slice will not fail the check
              mockCD("collection1", "invalid_replica1", "invalid", true, Replica.State.DOWN));
      long unhealthy1 = HealthCheckHandler.findUnhealthyCores(node1Cores, clusterState);
      assertEquals(2, unhealthy1);

      // Node 2
      Collection<CloudDescriptor> node2Cores =
          Arrays.asList(
              mockCD("collection1", "slice1_replica2", "slice1", true, Replica.State.ACTIVE),
              mockCD("collection1", "slice1_replica4", "slice1", true, Replica.State.DOWN),
              mockCD(
                  "collection2", "slice1_replica1", "slice1", true, Replica.State.RECOVERY_FAILED));
      long unhealthy2 = HealthCheckHandler.findUnhealthyCores(node2Cores, clusterState);
      assertEquals(1, unhealthy2);
    }
  }

  /* Creates a minimal cloud descriptor for a core */
  private CloudDescriptor mockCD(
      String collection, String name, String shardId, boolean registered, Replica.State state) {
    Properties props = new Properties();
    props.put(CoreDescriptor.CORE_SHARD, shardId);
    props.put(CoreDescriptor.CORE_COLLECTION, collection);
    props.put(CoreDescriptor.CORE_NODE_NAME, name);
    CloudDescriptor cd = new CloudDescriptor(null, name, props);
    cd.setHasRegistered(registered);
    cd.setLastPublished(state);
    return cd;
  }
}
