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
import static org.apache.solr.common.params.CoreAdminParams.CORE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.api.model.PrepareCoreRecoveryRequestBody;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link PrepareCoreRecovery} */
public class PrepareCoreRecoveryAPITest extends SolrTestCaseJ4 {

  private PrepareCoreRecovery api;
  private CoreContainer mockCoreContainer;
  private CoreAdminHandler mockCoreAdminHandler;
  private CoreAdminHandler.CoreAdminAsyncTracker asyncTracker;
  private ArgumentCaptor<SolrQueryRequest> requestCaptor;
  private SolrQueryRequest realRequest;
  private SolrQueryResponse queryResponse;

  @BeforeClass
  public static void ensureWorkingMockito() {
    assumeWorkingMockito();
  }

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    mockCoreContainer = mock(CoreContainer.class);
    mockCoreAdminHandler = mock(CoreAdminHandler.class);
    when(mockCoreContainer.getMultiCoreHandler()).thenReturn(mockCoreAdminHandler);
    asyncTracker = new CoreAdminHandler.CoreAdminAsyncTracker();
    requestCaptor = ArgumentCaptor.forClass(SolrQueryRequest.class);
    realRequest = new SolrQueryRequestBase(null, new ModifiableSolrParams()) {};
    queryResponse = new SolrQueryResponse();
    api = new PrepareCoreRecovery(mockCoreContainer, asyncTracker, realRequest, queryResponse);
  }

  @Test
  public void testReportsErrorIfCoreNameMissing() {
    final SolrException thrown =
        expectThrows(
            SolrException.class,
            () -> api.prepareCoreForRecovery(null, new PrepareCoreRecoveryRequestBody()));
    assertEquals(400, thrown.code());
    assertEquals("Missing required parameter: core", thrown.getMessage());
  }

  @Test
  public void testAllParamsPassedCorrectly() throws Exception {
    final var requestBody = new PrepareCoreRecoveryRequestBody();
    requestBody.nodeName = "node1";
    requestBody.coreNodeName = "coreNode1";
    requestBody.state = "active";
    requestBody.checkLive = true;
    requestBody.onlyIfLeader = true;
    requestBody.onlyIfLeaderActive = true;

    api.prepareCoreForRecovery("coreName", requestBody);

    verify(mockCoreAdminHandler).handleRequestBody(requestCaptor.capture(), any());
    SolrParams params = requestCaptor.getValue().getParams();

    assertEquals("preprecovery", params.get(ACTION));
    assertEquals("coreName", params.get(CORE));
    assertEquals("node1", params.get("nodeName"));
    assertEquals("coreNode1", params.get("coreNodeName"));
    assertEquals("active", params.get("state"));
    assertEquals("true", params.get("checkLive"));
    assertEquals("true", params.get("onlyIfLeader"));
    assertEquals("true", params.get("onlyIfLeaderActive"));
  }
}
