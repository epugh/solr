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

import static org.apache.solr.security.PermissionNameProvider.Name.COLL_READ_PERM;

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.solr.client.api.endpoint.ListClusterNodesApi;
import org.apache.solr.client.api.model.ListClusterNodesResponse;
import org.apache.solr.client.api.model.ListClusterNodesResponse.NodeState;
import org.apache.solr.client.api.model.ListClusterNodesResponse.ShardReplicaCounts;
import org.apache.solr.cloud.OverseerTaskProcessor;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

/**
 * V2 API for listing live nodes in the SolrCloud cluster.
 *
 * <p>This API (GET /api/cluster/nodes) has no dedicated v1 equivalent; {@code
 * /admin/collections?action=CLUSTERSTATUS} with {@code liveNodes=true} is the closest v1 form.
 *
 * <p>With {@code detailed=true}, it instead returns per-node replica and leadership metadata (in
 * {@code nodesDetail}) for every live node, every node hosting a replica, and the overseer leader
 * -- the same accounting {@link NodeStatus} reports for one node at a time.
 */
public class ListClusterNodes extends AdminAPIBase implements ListClusterNodesApi {

  @Inject
  public ListClusterNodes(
      CoreContainer coreContainer, SolrQueryRequest req, SolrQueryResponse rsp) {
    super(coreContainer, req, rsp);
  }

  @Override
  @PermissionName(COLL_READ_PERM)
  public ListClusterNodesResponse listClusterNodes(Boolean detailed) throws Exception {
    final ListClusterNodesResponse response =
        instantiateJerseyResponse(ListClusterNodesResponse.class);
    validateZooKeeperAwareCoreContainer(coreContainer);

    final ZkStateReader zkStateReader = coreContainer.getZkController().getZkStateReader();
    final ClusterState clusterState = zkStateReader.getClusterState();
    final Set<String> liveNodes = clusterState.getLiveNodes();

    if (!Boolean.TRUE.equals(detailed)) {
      response.nodes = Set.copyOf(liveNodes);
      return response;
    }

    final String overseerLeaderNode =
        OverseerTaskProcessor.getLeaderNode(zkStateReader.getZkClient());
    final Map<String, NodeReplicaStats> statsByNode = NodeReplicaStats.computeAll(clusterState);

    final Set<String> allNodeNames = new TreeSet<>(liveNodes);
    allNodeNames.addAll(statsByNode.keySet());
    if (overseerLeaderNode != null) {
      allNodeNames.add(overseerLeaderNode);
    }

    final Map<String, NodeState> nodesDetail = new LinkedHashMap<>();
    for (String nodeName : allNodeNames) {
      final NodeReplicaStats stats = statsByNode.get(nodeName);
      final NodeState nodeState = new NodeState();
      nodeState.live = liveNodes.contains(nodeName);
      nodeState.overseerLeader = nodeName.equals(overseerLeaderNode);
      nodeState.leaders = stats == null ? 0 : stats.leaders;
      nodeState.replicas = stats == null ? 0 : stats.replicas;
      nodeState.collections = toShardReplicaCounts(stats);
      nodesDetail.put(nodeName, nodeState);
    }
    response.nodesDetail = nodesDetail;
    return response;
  }

  private static Map<String, Map<String, ShardReplicaCounts>> toShardReplicaCounts(
      NodeReplicaStats stats) {
    final Map<String, Map<String, ShardReplicaCounts>> result = new LinkedHashMap<>();
    if (stats == null) {
      return result;
    }
    for (var collEntry : stats.collections.entrySet()) {
      final Map<String, ShardReplicaCounts> shardMap = new LinkedHashMap<>();
      for (var shardEntry : collEntry.getValue().entrySet()) {
        final var raw = shardEntry.getValue();
        final var counts = new ShardReplicaCounts();
        counts.totalReplicas = raw.totalReplicas;
        counts.activeReplicas = raw.activeReplicas;
        counts.downReplicas = raw.downReplicas;
        shardMap.put(shardEntry.getKey(), counts);
      }
      result.put(collEntry.getKey(), shardMap);
    }
    return result;
  }
}
