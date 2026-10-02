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

import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.solr.client.api.endpoint.NodeStatusApi;
import org.apache.solr.client.api.model.NodeStatusResponse;
import org.apache.solr.client.api.model.NodeStatusResponse.ShardReplicaCounts;
import org.apache.solr.cloud.OverseerTaskProcessor;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.PermissionNameProvider;

/** V2 API implementation for {@link NodeStatusApi}. */
public class NodeStatus extends AdminAPIBase implements NodeStatusApi {

  @Inject
  public NodeStatus(CoreContainer coreContainer, SolrQueryRequest req, SolrQueryResponse rsp) {
    super(coreContainer, req, rsp);
  }

  @Override
  @PermissionName(PermissionNameProvider.Name.COLL_READ_PERM)
  public NodeStatusResponse getNodeStatus(String nodeName) throws Exception {
    final NodeStatusResponse response = instantiateJerseyResponse(NodeStatusResponse.class);
    validateZooKeeperAwareCoreContainer(coreContainer);

    final ZkStateReader zkStateReader = coreContainer.getZkController().getZkStateReader();
    final ClusterState clusterState = zkStateReader.getClusterState();
    final boolean live = clusterState.liveNodesContain(nodeName);
    final boolean overseerLeader =
        nodeName.equals(OverseerTaskProcessor.getLeaderNode(zkStateReader.getZkClient()));
    final NodeReplicaStats stats = NodeReplicaStats.computeAll(clusterState).get(nodeName);

    if (!live && !overseerLeader && stats == null) {
      throw new SolrException(
          SolrException.ErrorCode.NOT_FOUND, "Node '" + nodeName + "' not found");
    }

    response.nodeName = nodeName;
    response.live = live;
    response.overseerLeader = overseerLeader;
    response.leaders = stats == null ? 0 : stats.leaders;
    response.replicas = stats == null ? 0 : stats.replicas;
    response.collections = toShardReplicaCounts(stats);
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
