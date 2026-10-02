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
package org.apache.solr.client.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Response body for {@code GET /api/cluster/nodes/{nodeName}}. */
public class NodeStatusResponse extends SolrJerseyResponse {

  @JsonProperty public String nodeName;

  @JsonProperty("live")
  public Boolean live;

  @JsonProperty("overseerLeader")
  public Boolean overseerLeader;

  @JsonProperty public Integer leaders;
  @JsonProperty public Integer replicas;

  @JsonProperty public Map<String, Map<String, ShardReplicaCounts>> collections;

  /** Replica counts, scoped to the replicas of one shard that live on this node. */
  public static class ShardReplicaCounts {
    @JsonProperty public Integer totalReplicas;
    @JsonProperty public Integer activeReplicas;
    @JsonProperty public Integer downReplicas;
  }
}
