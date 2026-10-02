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
package org.apache.solr.client.api.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.apache.solr.client.api.model.NodeStatusResponse;

/**
 * V2 API definition for fetching replica and leadership metadata about a single node in the
 * SolrCloud cluster.
 *
 * <p>This API (GET /api/cluster/nodes/{nodeName}) has no v1 equivalent; it reports a per-node view
 * of the same underlying cluster state that {@code /admin/collections?action=CLUSTERSTATUS} and
 * {@code GET /api/collections?detailed=true} report per-collection.
 */
@Path("/cluster/nodes/{nodeName}")
public interface NodeStatusApi {

  @GET
  @Operation(
      summary = "Fetches replica and leadership metadata about the specified node",
      tags = {"cluster"})
  NodeStatusResponse getNodeStatus(
      @Parameter(description = "The name of the node to return metadata for", required = true)
          @PathParam("nodeName")
          String nodeName)
      throws Exception;
}
