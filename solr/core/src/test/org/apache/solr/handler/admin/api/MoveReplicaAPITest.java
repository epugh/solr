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

import static org.apache.solr.common.params.CommonParams.ACTION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.api.model.MoveReplicaRequestBody;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.CollectionsHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link MoveReplica} */
public class MoveReplicaAPITest extends SolrTestCaseJ4 {

  private MoveReplica api;
  private CoreContainer mockCoreContainer;
  private CollectionsHandler mockCollectionsHandler;
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
    mockCollectionsHandler = mock(CollectionsHandler.class);
    when(mockCoreContainer.getCollectionsHandler()).thenReturn(mockCollectionsHandler);
    requestCaptor = ArgumentCaptor.forClass(SolrQueryRequest.class);
    realRequest = new SolrQueryRequestBase(null, new ModifiableSolrParams()) {};
    queryResponse = new SolrQueryResponse();
    api = new MoveReplica(mockCoreContainer, realRequest, queryResponse);
  }

  @Test
  public void testReportsErrorIfCollectionNameMissing() {
    final SolrException thrown =
        expectThrows(
            SolrException.class, () -> api.moveReplica(null, new MoveReplicaRequestBody()));
    assertEquals(400, thrown.code());
    assertEquals("Missing required parameter: collection", thrown.getMessage());
  }

  @Test
  public void testAllParamsPassedCorrectly() throws Exception {
    final var requestBody = new MoveReplicaRequestBody();
    requestBody.targetNode = "targetNode1";
    requestBody.replica = "replica1";
    requestBody.shard = "shard1";
    requestBody.sourceNode = "sourceNode1";
    requestBody.waitForFinalState = true;
    requestBody.timeout = 300;
    requestBody.inPlaceMove = false;
    requestBody.followAliases = true;

    api.moveReplica("collName", requestBody);

    verify(mockCollectionsHandler).handleRequestBody(requestCaptor.capture(), any());
    SolrParams params = requestCaptor.getValue().getParams();

    assertEquals("movereplica", params.get(ACTION));
    assertEquals("collName", params.get("collection"));
    assertEquals("targetNode1", params.get("targetNode"));
    assertEquals("replica1", params.get("replica"));
    assertEquals("shard1", params.get("shard"));
    assertEquals("sourceNode1", params.get("sourceNode"));
    assertEquals("true", params.get("waitForFinalState"));
    assertEquals("300", params.get("timeout"));
    assertEquals("false", params.get("inPlaceMove"));
    assertEquals("true", params.get("followAliases"));
  }
}
