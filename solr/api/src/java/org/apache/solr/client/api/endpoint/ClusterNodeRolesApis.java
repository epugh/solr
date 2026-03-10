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
import org.apache.solr.client.api.model.SolrJerseyResponse;

/** V2 API definitions for reading node roles in a Solr cluster. */
@Path("/cluster/node-roles")
public interface ClusterNodeRolesApis {

  @GET
  @Operation(
      summary = "Retrieve all node roles in the cluster.",
      tags = {"cluster"})
  SolrJerseyResponse getAllNodeRoles() throws Exception;

  @GET
  @Path("/supported")
  @Operation(
      summary = "Retrieve all supported node roles and their modes.",
      tags = {"cluster"})
  SolrJerseyResponse getSupportedRoles() throws Exception;

  @GET
  @Path("/role/{role}")
  @Operation(
      summary = "Retrieve all nodes assigned to a specific role.",
      tags = {"cluster"})
  SolrJerseyResponse getNodesForRole(
      @Parameter(description = "The role to query.", required = true) @PathParam("role")
          String role)
      throws Exception;

  @GET
  @Path("/role/{role}/{mode}")
  @Operation(
      summary = "Retrieve nodes assigned to a specific role and mode.",
      tags = {"cluster"})
  SolrJerseyResponse getNodesForRoleAndMode(
      @Parameter(description = "The role to query.", required = true) @PathParam("role")
          String role,
      @Parameter(description = "The mode to query.", required = true) @PathParam("mode")
          String mode)
      throws Exception;

  @GET
  @Path("/node/{node}")
  @Operation(
      summary = "Retrieve all roles assigned to a specific node.",
      tags = {"cluster"})
  SolrJerseyResponse getRolesForNode(
      @Parameter(description = "The node name to query.", required = true) @PathParam("node")
          String node)
      throws Exception;
}
