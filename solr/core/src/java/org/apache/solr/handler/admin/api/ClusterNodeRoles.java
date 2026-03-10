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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.solr.client.api.endpoint.ClusterNodeRolesApis;
import org.apache.solr.client.api.model.FlexibleSolrJerseyResponse;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeRoles;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.PermissionNameProvider;

/**
 * V2 API implementation for reading node roles in a Solr cluster.
 *
 * <p>These APIs (/api/cluster/node-roles and sub-paths) are analogous to v1 cluster node-role
 * operations.
 */
public class ClusterNodeRoles extends AdminAPIBase implements ClusterNodeRolesApis {

  @Inject
  public ClusterNodeRoles(
      CoreContainer coreContainer,
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse) {
    super(coreContainer, solrQueryRequest, solrQueryResponse);
  }

  @Override
  @PermissionName(PermissionNameProvider.Name.COLL_READ_PERM)
  public SolrJerseyResponse getAllNodeRoles() throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    response.setUnknownProperty(
        "node-roles",
        readRecursive(
            ZkStateReader.NODE_ROLES,
            coreContainer.getZkController().getSolrCloudManager().getDistribStateManager(),
            3));
    return response;
  }

  @Override
  @PermissionName(PermissionNameProvider.Name.COLL_READ_PERM)
  public SolrJerseyResponse getSupportedRoles() throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    Map<String, Object> roleModesSupportedMap = new HashMap<>();
    for (NodeRoles.Role role : NodeRoles.Role.values()) {
      roleModesSupportedMap.put(role.toString(), Map.of("modes", role.supportedModes()));
    }
    response.setUnknownProperty("supported-roles", roleModesSupportedMap);
    return response;
  }

  @Override
  @PermissionName(PermissionNameProvider.Name.COLL_READ_PERM)
  public SolrJerseyResponse getNodesForRole(String role) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    response.setUnknownProperty(
        "node-roles",
        Map.of(
            role,
            readRecursive(
                ZkStateReader.NODE_ROLES + "/" + role,
                coreContainer.getZkController().getSolrCloudManager().getDistribStateManager(),
                2)));
    return response;
  }

  @Override
  @PermissionName(PermissionNameProvider.Name.COLL_READ_PERM)
  public SolrJerseyResponse getNodesForRoleAndMode(String role, String mode) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    List<String> nodes =
        coreContainer
            .getZkController()
            .getSolrCloudManager()
            .getDistribStateManager()
            .listData(ZkStateReader.NODE_ROLES + "/" + role + "/" + mode);
    response.setUnknownProperty("node-roles", Map.of(role, Collections.singletonMap(mode, nodes)));
    return response;
  }

  @Override
  @PermissionName(PermissionNameProvider.Name.COLL_READ_PERM)
  @SuppressWarnings("unchecked")
  public SolrJerseyResponse getRolesForNode(String node) throws Exception {
    fetchAndValidateZooKeeperAwareCoreContainer();
    final FlexibleSolrJerseyResponse response =
        instantiateJerseyResponse(FlexibleSolrJerseyResponse.class);
    Map<String, Map<String, Set<String>>> roles =
        (Map<String, Map<String, Set<String>>>)
            readRecursive(
                ZkStateReader.NODE_ROLES,
                coreContainer.getZkController().getSolrCloudManager().getDistribStateManager(),
                3);
    for (String role : roles.keySet()) {
      for (String mode : roles.get(role).keySet()) {
        if (roles.get(role).get(mode).isEmpty()) continue;
        Set<String> nodes = roles.get(role).get(mode);
        if (nodes.contains(node)) {
          response.setUnknownProperty(role, mode);
        }
      }
    }
    return response;
  }

  static Object readRecursive(String path, DistribStateManager zk, int depth) {
    if (depth == 0) return null;
    Map<String, Object> result;
    try {
      List<String> children = zk.listData(path);
      if (children != null && !children.isEmpty()) {
        result = new HashMap<>();
      } else {
        return Collections.emptySet();
      }
      for (String child : children) {
        Object c = readRecursive(path + "/" + child, zk, depth - 1);
        result.put(child, c);
      }
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
    if (depth == 1) {
      return result.keySet();
    } else {
      return result;
    }
  }
}
