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

import static org.apache.solr.common.params.CommonAdminParams.SPLIT_KEY;
import static org.apache.solr.common.params.CommonParams.ACTION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.api.model.SplitShardRequestBody;
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

/** Unit tests for {@link SplitShard} */
public class SplitShardAPITest extends SolrTestCaseJ4 {

  private SplitShard api;
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
    api = new SplitShard(mockCoreContainer, realRequest, queryResponse);
  }

  @Test
  public void testReportsErrorIfCollectionNameMissing() {
    final SolrException thrown =
        expectThrows(SolrException.class, () -> api.splitShard(null, new SplitShardRequestBody()));
    assertEquals(400, thrown.code());
    assertEquals("Missing required parameter: collection", thrown.getMessage());
  }

  @Test
  public void testAllParamsPassedCorrectly() throws Exception {
    final var requestBody = new SplitShardRequestBody();
    requestBody.shard = "shard1";
    requestBody.ranges = "someRanges";
    requestBody.splitKey = "someSplitKey";
    requestBody.numSubShards = 3;
    requestBody.splitFuzz = "0.1";
    requestBody.timing = true;
    requestBody.splitByPrefix = true;
    requestBody.followAliases = true;
    requestBody.splitMethod = "rewrite";
    requestBody.async = "someAsyncId";
    requestBody.waitForFinalState = true;
    requestBody.coreProperties = Map.of("prop1", "val1");

    api.splitShard("collName", requestBody);

    verify(mockCollectionsHandler).handleRequestBody(requestCaptor.capture(), any());
    SolrParams params = requestCaptor.getValue().getParams();

    assertEquals("splitshard", params.get(ACTION));
    assertEquals("collName", params.get("collection"));
    assertEquals("shard1", params.get("shard"));
    assertEquals("someRanges", params.get("ranges"));
    assertEquals("someSplitKey", params.get(SPLIT_KEY));
    assertEquals("3", params.get("numSubShards"));
    assertEquals("0.1", params.get("splitFuzz"));
    assertEquals("true", params.get("timing"));
    assertEquals("true", params.get("splitByPrefix"));
    assertEquals("true", params.get("followAliases"));
    assertEquals("rewrite", params.get("splitMethod"));
    assertEquals("someAsyncId", params.get("async"));
    assertEquals("true", params.get("waitForFinalState"));
    assertEquals("val1", params.get("property.prop1"));
  }
}
