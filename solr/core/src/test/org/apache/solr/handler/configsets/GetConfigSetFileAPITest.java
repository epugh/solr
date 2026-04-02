/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.configsets;

import static org.apache.solr.SolrTestCaseJ4.assumeWorkingMockito;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.solr.SolrTestCase;
import org.apache.solr.client.api.model.ConfigSetFileContentsResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.FileSystemConfigSetService;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Unit tests for {@link GetConfigSetFile}. */
public class GetConfigSetFileAPITest extends SolrTestCase {

  private CoreContainer mockCoreContainer;
  private FileSystemConfigSetService configSetService;
  private Path configSetBase;

  @BeforeClass
  public static void ensureWorkingMockito() {
    assumeWorkingMockito();
  }

  @Before
  public void initConfigSetService() throws Exception {
    configSetBase = createTempDir("configsets");
    // Use an anonymous subclass to access the protected testing constructor
    configSetService = new FileSystemConfigSetService(configSetBase) {};
    mockCoreContainer = mock(CoreContainer.class);
    when(mockCoreContainer.getConfigSetService()).thenReturn(configSetService);
  }

  /** Creates a configset directory with one file. */
  private void createConfigSetWithFile(String configSetName, String filePath, String content)
      throws Exception {
    Path dir = configSetBase.resolve(configSetName);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(filePath), content, StandardCharsets.UTF_8);
  }

  @Test
  public void testMissingConfigSetNameThrowsBadRequest() {
    final var api = new GetConfigSetFile(mockCoreContainer, null, null);
    final var ex =
        assertThrows(SolrException.class, () -> api.getConfigSetFile(null, "schema.xml"));
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex.code());

    final var ex2 = assertThrows(SolrException.class, () -> api.getConfigSetFile("", "schema.xml"));
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex2.code());
  }

  @Test
  public void testMissingFilePathThrowsBadRequest() {
    final var api = new GetConfigSetFile(mockCoreContainer, null, null);
    final var ex = assertThrows(SolrException.class, () -> api.getConfigSetFile("myconfig", null));
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex.code());

    final var ex2 = assertThrows(SolrException.class, () -> api.getConfigSetFile("myconfig", ""));
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex2.code());
  }

  @Test
  public void testNonExistentConfigSetThrowsNotFound() {
    // "missing" was never created in configSetBase, so checkConfigExists returns false
    final var api = new GetConfigSetFile(mockCoreContainer, null, null);
    final var ex =
        assertThrows(SolrException.class, () -> api.getConfigSetFile("missing", "schema.xml"));
    assertEquals(SolrException.ErrorCode.NOT_FOUND.code, ex.code());
  }

  @Test
  public void testSuccessfulFileRead() throws Exception {
    final String configSetName = "myconfig";
    final String filePath = "schema.xml";
    final String fileContent = "<schema/>";
    createConfigSetWithFile(configSetName, filePath, fileContent);

    final var api = new GetConfigSetFile(mockCoreContainer, null, null);
    final ConfigSetFileContentsResponse response = api.getConfigSetFile(configSetName, filePath);

    assertNotNull(response);
    assertEquals(filePath, response.path);
    assertEquals(fileContent, response.content);
  }

  @Test
  public void testFileNotFoundInConfigSetThrowsNotFound() throws Exception {
    final String configSetName = "myconfig";
    // Create the configset directory but do NOT add the requested file
    Files.createDirectories(configSetBase.resolve(configSetName));

    final var api = new GetConfigSetFile(mockCoreContainer, null, null);
    final var ex =
        assertThrows(SolrException.class, () -> api.getConfigSetFile(configSetName, "missing.xml"));
    assertEquals(SolrException.ErrorCode.NOT_FOUND.code, ex.code());
  }

  @Test
  public void testEmptyFileReturnsEmptyContent() throws Exception {
    final String configSetName = "myconfig";
    final String filePath = "empty.xml";
    createConfigSetWithFile(configSetName, filePath, "");

    final var api = new GetConfigSetFile(mockCoreContainer, null, null);
    final ConfigSetFileContentsResponse response = api.getConfigSetFile(configSetName, filePath);

    assertNotNull(response);
    assertEquals(filePath, response.path);
    assertEquals("", response.content);
  }
}
