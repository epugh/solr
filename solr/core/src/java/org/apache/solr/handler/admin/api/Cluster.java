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

import static org.apache.solr.cloud.api.collections.CollectionHandlingUtils.REQUESTID;
import static org.apache.solr.common.params.CollectionParams.ACTION;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.ADDROLE;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.DELETESTATUS;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.OVERSEERSTATUS;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.REMOVEROLE;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.REQUESTSTATUS;
import static org.apache.solr.core.RateLimiterConfig.RL_CONFIG_KEY;
import static org.apache.solr.security.PermissionNameProvider.Name.COLL_EDIT_PERM;
import static org.apache.solr.security.PermissionNameProvider.Name.COLL_READ_PERM;

import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.apache.solr.client.api.endpoint.ClusterApis;
import org.apache.solr.client.api.model.AddRoleRequestBody;
import org.apache.solr.client.api.model.FlexibleSolrJerseyResponse;
import org.apache.solr.client.api.model.RateLimiterPayload;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterProperties;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.ClusterAPI;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

/**
 * V2 API implementation for cluster-level operations.
 *
 * <p>These APIs (/api/cluster and sub-paths) are analogous to the v1 /admin/collections endpoint
 * for cluster operations.
 */
public class Cluster extends AdminAPIBase implements ClusterApis {

  @Inject
  public Cluster(
      CoreContainer coreContainer,
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse) {
    super(coreContainer, solrQueryRequest, solrQueryResponse);
  }

  @Override
  @PermissionName(COLL_READ_PERM)
  public SolrJerseyResponse getClusterStatus() throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    coreContainer
        .getCollectionsHandler()
        .handleRequestBody(
            ClusterAPI.wrapParams(
                solrQueryRequest,
                Map.of(
                    CommonParams.ACTION,
                    CollectionParams.CollectionAction.CLUSTERSTATUS.toLower())),
            solrQueryResponse);
    copyFromResponse(response, solrQueryResponse);
    return response;
  }

  @Override
  @PermissionName(COLL_READ_PERM)
  public SolrJerseyResponse getNodes() throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    response.setUnknownProperty(
        "nodes", coreContainer.getZkController().getClusterState().getLiveNodes());
    return response;
  }

  @Override
  @PermissionName(COLL_READ_PERM)
  public SolrJerseyResponse getOverseerStatus() throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    coreContainer
        .getCollectionsHandler()
        .handleRequestBody(
            ClusterAPI.wrapParams(solrQueryRequest, ACTION, OVERSEERSTATUS.lowerName),
            solrQueryResponse);
    copyFromResponse(response, solrQueryResponse);
    return response;
  }

  @Override
  @PermissionName(COLL_READ_PERM)
  public SolrJerseyResponse getCommandStatus(String requestId) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    coreContainer
        .getCollectionsHandler()
        .handleRequestBody(
            ClusterAPI.wrapParams(
                solrQueryRequest, Map.of(ACTION, REQUESTSTATUS.lowerName, REQUESTID, requestId)),
            solrQueryResponse);
    copyFromResponse(response, solrQueryResponse);
    return response;
  }

  @Override
  @PermissionName(COLL_EDIT_PERM)
  public SolrJerseyResponse deleteCommandStatus(String requestId) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    coreContainer
        .getCollectionsHandler()
        .handleRequestBody(
            ClusterAPI.wrapParams(
                solrQueryRequest, Map.of(ACTION, DELETESTATUS.lowerName, REQUESTID, requestId)),
            solrQueryResponse);
    copyFromResponse(response, solrQueryResponse);
    return response;
  }

  @Override
  @PermissionName(COLL_EDIT_PERM)
  public SolrJerseyResponse flushCommandStatus() throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final SolrJerseyResponse response = instantiateJerseyResponse(SolrJerseyResponse.class);
    coreContainer
        .getCollectionsHandler()
        .handleRequestBody(
            ClusterAPI.wrapParams(
                solrQueryRequest,
                Map.of(
                    ACTION,
                    DELETESTATUS.lowerName,
                    org.apache.solr.common.params.CollectionAdminParams.FLUSH,
                    "true")),
            solrQueryResponse);
    if (solrQueryResponse.getException() != null) {
      throw solrQueryResponse.getException();
    }
    return response;
  }

  @Override
  @PermissionName(COLL_EDIT_PERM)
  public SolrJerseyResponse addRole(AddRoleRequestBody requestBody) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    ensureRequiredRequestBodyProvided(requestBody);
    final SolrJerseyResponse response = instantiateJerseyResponse(SolrJerseyResponse.class);
    Map<String, Object> props = new HashMap<>();
    props.put("node", requestBody.node);
    props.put("role", requestBody.role);
    submitRemoteMessageAndHandleException(response, ADDROLE, new ZkNodeProps(props));
    return response;
  }

  @Override
  @PermissionName(COLL_EDIT_PERM)
  public SolrJerseyResponse removeRole(AddRoleRequestBody requestBody) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    ensureRequiredRequestBodyProvided(requestBody);
    final SolrJerseyResponse response = instantiateJerseyResponse(SolrJerseyResponse.class);
    Map<String, Object> props = new HashMap<>();
    props.put("node", requestBody.node);
    props.put("role", requestBody.role);
    submitRemoteMessageAndHandleException(response, REMOVEROLE, new ZkNodeProps(props));
    return response;
  }

  @Override
  @PermissionName(COLL_EDIT_PERM)
  public SolrJerseyResponse setRateLimiters(RateLimiterPayload requestBody) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final SolrJerseyResponse response = instantiateJerseyResponse(SolrJerseyResponse.class);
    if (requestBody == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Missing request body");
    }
    ClusterProperties clusterProperties =
        new ClusterProperties(coreContainer.getZkController().getZkClient());
    Map<String, Object> configMap = new HashMap<>();
    if (requestBody.enabled != null) configMap.put("enabled", requestBody.enabled);
    if (requestBody.guaranteedSlots != null)
      configMap.put("guaranteedSlots", requestBody.guaranteedSlots);
    if (requestBody.allowedRequests != null)
      configMap.put("allowedRequests", requestBody.allowedRequests);
    if (requestBody.slotBorrowingEnabled != null)
      configMap.put("slotBorrowingEnabled", requestBody.slotBorrowingEnabled);
    if (requestBody.slotAcquisitionTimeoutInMS != null)
      configMap.put("slotAcquisitionTimeoutInMS", requestBody.slotAcquisitionTimeoutInMS);
    try {
      clusterProperties.setClusterProperty(RL_CONFIG_KEY, configMap);
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error in API", e);
    }
    return response;
  }

  private void copyFromResponse(FlexibleSolrJerseyResponse response, SolrQueryResponse rsp)
      throws Exception {
    if (rsp.getException() != null) {
      throw rsp.getException();
    }
    for (Map.Entry<String, ?> entry : rsp.getValues()) {
      if (!entry.getKey().equals("responseHeader")) {
        response.setUnknownProperty(entry.getKey(), entry.getValue());
      }
    }
  }
}
