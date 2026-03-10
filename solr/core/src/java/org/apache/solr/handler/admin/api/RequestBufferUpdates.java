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

import static org.apache.solr.common.params.CoreAdminParams.ACTION;
import static org.apache.solr.common.params.CoreAdminParams.CoreAdminAction.REQUESTBUFFERUPDATES;
import static org.apache.solr.common.params.CoreAdminParams.NAME;
import static org.apache.solr.handler.ClusterAPI.wrapParams;
import static org.apache.solr.security.PermissionNameProvider.Name.CORE_EDIT_PERM;

import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.solr.client.api.endpoint.RequestBufferUpdatesApi;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

/**
 * V2 API implementation for starting update-buffering on a core.
 *
 * <p>This API (POST /v2/cores/coreName/request-buffer-updates) is analogous to the v1
 * /admin/cores?action=REQUESTBUFFERUPDATES command.
 */
public class RequestBufferUpdates extends CoreAdminAPIBase implements RequestBufferUpdatesApi {

  @Inject
  public RequestBufferUpdates(
      CoreContainer coreContainer,
      CoreAdminHandler.CoreAdminAsyncTracker coreAdminAsyncTracker,
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse) {
    super(coreContainer, coreAdminAsyncTracker, solrQueryRequest, solrQueryResponse);
  }

  @Override
  @PermissionName(CORE_EDIT_PERM)
  public SolrJerseyResponse requestBufferUpdates(String coreName) throws Exception {
    ensureRequiredParameterProvided(NAME, coreName);
    final SolrJerseyResponse response = instantiateJerseyResponse(SolrJerseyResponse.class);
    final Map<String, Object> v1Params = new HashMap<>();
    v1Params.put(ACTION, REQUESTBUFFERUPDATES.name().toLowerCase(Locale.ROOT));
    v1Params.put(NAME, coreName);
    coreContainer.getMultiCoreHandler().handleRequestBody(wrapParams(req, v1Params), rsp);
    return response;
  }
}
