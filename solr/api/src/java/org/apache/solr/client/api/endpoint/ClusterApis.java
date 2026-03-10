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
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.apache.solr.client.api.model.AddRoleRequestBody;
import org.apache.solr.client.api.model.RateLimiterPayload;
import org.apache.solr.client.api.model.SolrJerseyResponse;

/** V2 API definitions for cluster-level operations in a Solr cluster. */
@Path("/cluster")
public interface ClusterApis {

  @GET
  @Operation(
      summary = "Retrieve the cluster status.",
      tags = {"cluster"})
  SolrJerseyResponse getClusterStatus() throws Exception;

  @GET
  @Path("/nodes")
  @Operation(
      summary = "Retrieve the list of live nodes in the cluster.",
      tags = {"cluster"})
  SolrJerseyResponse getNodes() throws Exception;

  @GET
  @Path("/overseer")
  @Operation(
      summary = "Retrieve the status of the cluster overseer.",
      tags = {"cluster"})
  SolrJerseyResponse getOverseerStatus() throws Exception;

  @GET
  @Path("/command-status/{requestId}")
  @Operation(
      summary = "Retrieve the status of an async command.",
      tags = {"cluster"})
  SolrJerseyResponse getCommandStatus(
      @Parameter(description = "The async request ID to look up.", required = true)
          @PathParam("requestId")
          String requestId)
      throws Exception;

  @DELETE
  @Path("/command-status/{requestId}")
  @Operation(
      summary = "Delete the status of a completed async command.",
      tags = {"cluster"})
  SolrJerseyResponse deleteCommandStatus(
      @Parameter(description = "The async request ID to delete.", required = true)
          @PathParam("requestId")
          String requestId)
      throws Exception;

  @DELETE
  @Path("/command-status")
  @Operation(
      summary = "Flush the status of all completed async commands.",
      tags = {"cluster"})
  SolrJerseyResponse flushCommandStatus() throws Exception;

  @POST
  @Path("/roles")
  @Operation(
      summary = "Add an overseer role to a node.",
      tags = {"cluster"})
  SolrJerseyResponse addRole(
      @RequestBody(description = "The node and role to assign.", required = true)
          AddRoleRequestBody requestBody)
      throws Exception;

  @DELETE
  @Path("/roles")
  @Operation(
      summary = "Remove an overseer role from a node.",
      tags = {"cluster"})
  SolrJerseyResponse removeRole(
      @RequestBody(description = "The node and role to remove.", required = true)
          AddRoleRequestBody requestBody)
      throws Exception;

  @POST
  @Path("/ratelimiters")
  @Operation(
      summary = "Set rate limiter configuration for the cluster.",
      tags = {"cluster"})
  SolrJerseyResponse setRateLimiters(
      @RequestBody(description = "Rate limiter configuration.", required = true)
          RateLimiterPayload requestBody)
      throws Exception;
}
