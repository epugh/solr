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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.api.model.NodePropertiesResponse;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeConfig;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Unit tests for {@link NodeProperties} */
public class NodePropertiesAPITest extends SolrTestCaseJ4 {

  private NodeProperties api;
  private CoreContainer mockCoreContainer;
  private NodeConfig mockNodeConfig;

  @BeforeClass
  public static void ensureWorkingMockito() {
    assumeWorkingMockito();
  }

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    mockCoreContainer = mock(CoreContainer.class);
    mockNodeConfig = mock(NodeConfig.class);
    when(mockCoreContainer.getNodeConfig()).thenReturn(mockNodeConfig);
    api = new NodeProperties(mockCoreContainer);
  }

  @Test
  public void testGetSpecificProperty() throws Exception {
    when(mockNodeConfig.getRedactedSysPropValue("java.home")).thenReturn("someValue");

    NodePropertiesResponse response = api.getProperties("java.home");

    assertNotNull(response.systemProperties);
    assertEquals(1, response.systemProperties.size());
    assertEquals("someValue", response.systemProperties.get("java.home"));
  }

  @Test
  public void testGetAllPropertiesWhenNameIsNull() throws Exception {
    when(mockNodeConfig.getRedactedSysPropValue(any())).thenAnswer(inv -> inv.getArgument(0));

    NodePropertiesResponse response = api.getProperties(null);

    assertNotNull(response.systemProperties);
    assertFalse("Expected non-empty system properties", response.systemProperties.isEmpty());
    assertTrue(
        "Expected 'java.home' to be present in system properties",
        response.systemProperties.containsKey("java.home"));
  }

  private static String any() {
    return org.mockito.ArgumentMatchers.anyString();
  }
}
