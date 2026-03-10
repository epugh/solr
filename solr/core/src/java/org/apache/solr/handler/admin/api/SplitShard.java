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

import static org.apache.solr.common.params.CollectionAdminParams.COLLECTION;
import static org.apache.solr.common.params.CollectionAdminParams.PROPERTY_PREFIX;
import static org.apache.solr.common.params.CommonParams.ACTION;
import static org.apache.solr.handler.ClusterAPI.wrapParams;
import static org.apache.solr.handler.api.V2ApiUtils.flattenMapWithPrefix;
import static org.apache.solr.security.PermissionNameProvider.Name.COLL_EDIT_PERM;

import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.apache.solr.client.api.endpoint.SplitShardApi;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.client.api.model.SplitShardRequestBody;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.CommonAdminParams;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

/**
 * V2 API implementation for splitting an existing shard into multiple pieces.
 *
 * <p>This API (POST /v2/collections/collectionName/shards/split) is analogous to the v1
 * /admin/collections?action=SPLITSHARD command.
 */
public class SplitShard extends AdminAPIBase implements SplitShardApi {

  @Inject
  public SplitShard(
      CoreContainer coreContainer,
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse) {
    super(coreContainer, solrQueryRequest, solrQueryResponse);
  }

  @Override
  @PermissionName(COLL_EDIT_PERM)
  public SolrJerseyResponse splitShard(String collectionName, SplitShardRequestBody requestBody)
      throws Exception {
    ensureRequiredParameterProvided(COLLECTION, collectionName);
    final SolrJerseyResponse response = instantiateJerseyResponse(SolrJerseyResponse.class);
    final Map<String, Object> v1Params = new HashMap<>();
    v1Params.put(ACTION, CollectionParams.CollectionAction.SPLITSHARD.toLower());
    v1Params.put(COLLECTION, collectionName);
    if (requestBody != null) {
      if (requestBody.shard != null) v1Params.put("shard", requestBody.shard);
      if (requestBody.ranges != null) v1Params.put("ranges", requestBody.ranges);
      if (StrUtils.isNotNullOrEmpty(requestBody.splitKey)) {
        v1Params.put(CommonAdminParams.SPLIT_KEY, requestBody.splitKey);
      }
      if (requestBody.numSubShards != null) v1Params.put("numSubShards", requestBody.numSubShards);
      if (requestBody.splitFuzz != null) v1Params.put("splitFuzz", requestBody.splitFuzz);
      if (requestBody.timing != null) v1Params.put("timing", requestBody.timing);
      if (requestBody.splitByPrefix != null)
        v1Params.put("splitByPrefix", requestBody.splitByPrefix);
      if (requestBody.followAliases != null)
        v1Params.put("followAliases", requestBody.followAliases);
      if (requestBody.splitMethod != null) v1Params.put("splitMethod", requestBody.splitMethod);
      if (requestBody.async != null) v1Params.put("async", requestBody.async);
      if (requestBody.waitForFinalState != null)
        v1Params.put("waitForFinalState", requestBody.waitForFinalState);
      flattenMapWithPrefix(requestBody.coreProperties, v1Params, PROPERTY_PREFIX);
    }
    coreContainer
        .getCollectionsHandler()
        .handleRequestBody(wrapParams(solrQueryRequest, v1Params), solrQueryResponse);
    return response;
  }
}
