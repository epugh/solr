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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.InfoHandler;
import org.apache.solr.handler.admin.SystemInfoHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Unit tests for {@link NodeSystemInfo} */
public class NodeSystemInfoAPITest extends SolrTestCaseJ4 {

  private NodeSystemInfo api;
  private CoreContainer mockCoreContainer;
  private InfoHandler mockInfoHandler;
  private SystemInfoHandler mockSystemInfoHandler;
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
    mockInfoHandler = mock(InfoHandler.class);
    mockSystemInfoHandler = mock(SystemInfoHandler.class);
    when(mockCoreContainer.getInfoHandler()).thenReturn(mockInfoHandler);
    when(mockInfoHandler.getSystemInfoHandler()).thenReturn(mockSystemInfoHandler);
    realRequest = new SolrQueryRequestBase(null, new ModifiableSolrParams()) {};
    queryResponse = new SolrQueryResponse();
    api = new NodeSystemInfo(mockCoreContainer, realRequest, queryResponse);
  }

  @Test
  public void testDelegatesToSystemInfoHandler() throws Exception {
    api.getSystemInfo();

    verify(mockSystemInfoHandler).handleRequestBody(any(), any());
  }
}
