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

import static org.apache.solr.common.cloud.ZkStateReader.CORE_NODE_NAME_PROP;
import static org.apache.solr.common.params.CoreAdminParams.ACTION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.api.model.RejoinLeaderElectionRequestBody;
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

/** Unit tests for {@link RejoinLeaderElection} */
public class RejoinLeaderElectionAPITest extends SolrTestCaseJ4 {

  private RejoinLeaderElection api;
  private CoreContainer mockCoreContainer;
  private CoreAdminHandler mockCoreAdminHandler;
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
    requestCaptor = ArgumentCaptor.forClass(SolrQueryRequest.class);
    realRequest = new SolrQueryRequestBase(null, new ModifiableSolrParams()) {};
    queryResponse = new SolrQueryResponse();
    api = new RejoinLeaderElection(mockCoreContainer, realRequest, queryResponse);
  }

  @Test
  public void testAllParamsPassedCorrectly() throws Exception {
    final var requestBody = new RejoinLeaderElectionRequestBody();
    requestBody.collection = "myCollection";
    requestBody.coreNodeName = "myCoreNode";
    requestBody.core = "myCore";
    requestBody.rejoinAtHead = true;

    api.rejoinLeaderElection(requestBody);

    verify(mockCoreAdminHandler).handleRequestBody(requestCaptor.capture(), any());
    SolrParams params = requestCaptor.getValue().getParams();

    assertEquals("rejoinleaderelection", params.get(ACTION));
    assertEquals("myCollection", params.get("collection"));
    assertEquals("myCoreNode", params.get(CORE_NODE_NAME_PROP));
    assertEquals("myCore", params.get("core"));
    assertEquals("true", params.get("rejoinAtHead"));
  }
}
