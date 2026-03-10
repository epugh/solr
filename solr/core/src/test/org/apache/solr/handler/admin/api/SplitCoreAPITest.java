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
import static org.apache.solr.common.params.CommonParams.PATH;
import static org.apache.solr.common.params.CoreAdminParams.ACTION;
import static org.apache.solr.common.params.CoreAdminParams.CORE;
import static org.apache.solr.common.params.CoreAdminParams.TARGET_CORE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.api.model.SplitCoreRequestBody;
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

/** Unit tests for {@link SplitCore} */
public class SplitCoreAPITest extends SolrTestCaseJ4 {

  private SplitCore api;
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
    api = new SplitCore(mockCoreContainer, asyncTracker, realRequest, queryResponse);
  }

  @Test
  public void testReportsErrorIfCoreNameMissing() {
    final SolrException thrown =
        expectThrows(SolrException.class, () -> api.splitCore(null, new SplitCoreRequestBody()));
    assertEquals(400, thrown.code());
    assertEquals("Missing required parameter: core", thrown.getMessage());
  }

  @Test
  public void testAllParamsPassedCorrectly() throws Exception {
    final var requestBody = new SplitCoreRequestBody();
    requestBody.path = List.of("path1", "path2");
    requestBody.targetCore = List.of("core1", "core2");
    requestBody.splitKey = "splitKey1";
    requestBody.splitMethod = "rewrite";
    requestBody.getRanges = true;
    requestBody.ranges = "range1,range2";
    requestBody.async = "asyncId";

    api.splitCore("coreName", requestBody);

    verify(mockCoreAdminHandler).handleRequestBody(requestCaptor.capture(), any());
    SolrParams params = requestCaptor.getValue().getParams();

    assertEquals("split", params.get(ACTION));
    assertEquals("coreName", params.get(CORE));
    assertArrayEquals(new String[] {"path1", "path2"}, params.getParams(PATH));
    assertArrayEquals(new String[] {"core1", "core2"}, params.getParams(TARGET_CORE));
    assertEquals("splitKey1", params.get(SPLIT_KEY));
    assertEquals("rewrite", params.get("splitMethod"));
    assertEquals("true", params.get("getRanges"));
    assertEquals("range1,range2", params.get("ranges"));
    assertEquals("asyncId", params.get("async"));
  }
}
