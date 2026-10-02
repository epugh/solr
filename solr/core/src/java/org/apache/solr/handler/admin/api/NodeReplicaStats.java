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

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;

/**
 * Replica and leadership accounting for the cluster/nodes endpoints, grouped by node.
 *
 * <p>Internal helper shared by {@link NodeStatus} (single node) and {@link ListClusterNodes}
 * ({@code detailed=true}), so both report identical per-node counts. Not a public API model.
 */
final class NodeReplicaStats {
  int leaders;
  int replicas;
  final Map<String, Map<String, ShardReplicaCounts>> collections = new LinkedHashMap<>();

  static final class ShardReplicaCounts {
    int totalReplicas;
    int activeReplicas;
    int downReplicas;
  }

  /** Returns per-node stats for every node that hosts at least one replica. */
  static Map<String, NodeReplicaStats> computeAll(ClusterState clusterState) {
    final Map<String, NodeReplicaStats> result = new LinkedHashMap<>();
    for (var collection : clusterState.collectionStream().toList()) {
      for (var slice : collection.getSlices()) {
        for (Replica replica : slice.getReplicas()) {
          final NodeReplicaStats stats =
              result.computeIfAbsent(replica.getNodeName(), k -> new NodeReplicaStats());
          stats.replicas++;
          if (replica.isLeader()) {
            stats.leaders++;
          }

          final ShardReplicaCounts counts =
              stats
                  .collections
                  .computeIfAbsent(collection.getName(), k -> new LinkedHashMap<>())
                  .computeIfAbsent(slice.getName(), k -> new ShardReplicaCounts());
          counts.totalReplicas++;
          switch (replica.getState()) {
            case ACTIVE -> counts.activeReplicas++;
            case DOWN, RECOVERY_FAILED -> counts.downReplicas++;
            default -> {
              // RECOVERING counts toward totalReplicas only.
            }
          }
        }
      }
    }
    return result;
  }
}
