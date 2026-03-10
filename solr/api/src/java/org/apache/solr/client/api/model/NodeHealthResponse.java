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
import io.swagger.v3.oas.annotations.media.Schema;

/** Response model for node health check API. */
public class NodeHealthResponse extends SolrJerseyResponse {

  @JsonProperty("status")
  @Schema(description = "The health status of the node: 'OK' or 'FAILURE'.")
  public String status;

  @JsonProperty("message")
  @Schema(description = "An optional message providing additional details about the node health.")
  public String message;

  @JsonProperty("num_cores_unhealthy")
  @Schema(description = "The number of cores that are not in a healthy state.")
  public Long numCoresUnhealthy;
}
