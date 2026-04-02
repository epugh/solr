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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.ConfigSetService;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Unit tests for {@link DownloadConfigSet}. */
public class DownloadConfigSetAPITest extends SolrTestCase {

  private CoreContainer mockCoreContainer;
  private ConfigSetService mockConfigSetService;
  private SolrQueryRequest mockRequest;
  private SolrQueryResponse mockResponse;

  @BeforeClass
  public static void ensureWorkingMockito() {
    assumeWorkingMockito();
  }

  @Before
  public void setUpMocks() {
    mockCoreContainer = mock(CoreContainer.class);
    mockConfigSetService = mock(ConfigSetService.class);
    mockRequest = mock(SolrQueryRequest.class);
    mockResponse = mock(SolrQueryResponse.class);
    when(mockCoreContainer.getConfigSetService()).thenReturn(mockConfigSetService);
  }

  @Test
  public void testMissingConfigSetNameThrowsBadRequest() {
    final var api = new DownloadConfigSet(mockCoreContainer, mockRequest, mockResponse);
    final var ex = assertThrows(SolrException.class, () -> api.downloadConfigSet(null, null));
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex.code());

    final var ex2 = assertThrows(SolrException.class, () -> api.downloadConfigSet("", null));
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, ex2.code());
  }

  @Test
  public void testNonExistentConfigSetThrowsNotFound() throws Exception {
    when(mockConfigSetService.checkConfigExists("missing")).thenReturn(false);

    final var api = new DownloadConfigSet(mockCoreContainer, mockRequest, mockResponse);
    final var ex = assertThrows(SolrException.class, () -> api.downloadConfigSet("missing", null));
    assertEquals(SolrException.ErrorCode.NOT_FOUND.code, ex.code());
  }

  /** Stubs {@code configSetService.downloadConfig(configSetId, dir)} to write one file. */
  private void stubDownloadConfig(String configSetId, String fileName, String content)
      throws IOException {
    doAnswer(
            inv -> {
              Path dir = inv.getArgument(1);
              Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
              return null;
            })
        .when(mockConfigSetService)
        .downloadConfig(eq(configSetId), any(Path.class));
  }

  @Test
  public void testSuccessfulDownloadReturnsZipResponse() throws Exception {
    when(mockConfigSetService.checkConfigExists("myconfig")).thenReturn(true);
    stubDownloadConfig("myconfig", "solrconfig.xml", "<config/>");

    final var api = new DownloadConfigSet(mockCoreContainer, mockRequest, mockResponse);
    final Response response = api.downloadConfigSet("myconfig", null);

    assertNotNull(response);
    assertEquals(200, response.getStatus());
    assertEquals("application/zip", response.getMediaType().toString());
    assertTrue(
        String.valueOf(response.getHeaderString("Content-Disposition"))
            .contains("myconfig_configset.zip"));
  }

  @Test
  public void testFilenameIsSanitized() throws Exception {
    final String unsafeName = "my/config<name>";
    when(mockConfigSetService.checkConfigExists(unsafeName)).thenReturn(true);
    stubDownloadConfig(unsafeName, "schema.xml", "<schema/>");

    final var api = new DownloadConfigSet(mockCoreContainer, mockRequest, mockResponse);
    final Response response = api.downloadConfigSet(unsafeName, null);

    assertNotNull(response);
    final String disposition = response.getHeaderString("Content-Disposition");
    assertFalse(
        "filename must not contain unsafe characters",
        disposition.contains("/") || disposition.contains("<") || disposition.contains(">"));
    assertTrue(disposition.contains("_configset.zip"));
  }

  @Test
  public void testDisplayNameOverridesFilename() throws Exception {
    final String mutableId = "._designer_films";
    when(mockConfigSetService.checkConfigExists(mutableId)).thenReturn(true);
    stubDownloadConfig(mutableId, "schema.xml", "<schema/>");

    final var api = new DownloadConfigSet(mockCoreContainer, mockRequest, mockResponse);
    final Response response = api.downloadConfigSet(mutableId, "films");

    assertNotNull(response);
    assertEquals(200, response.getStatus());
    final String disposition = response.getHeaderString("Content-Disposition");
    assertTrue(
        "Content-Disposition should use the displayName 'films'",
        disposition.contains("films_configset.zip"));
    assertFalse(
        "Content-Disposition must not expose the internal mutable-ID prefix",
        disposition.contains("._designer_"));
  }

  @Test
  public void testBuildZipResponseUsesDisplayName() throws IOException {
    stubDownloadConfig("_designer_films", "schema.xml", "<schema/>");

    final Response response =
        DownloadConfigSet.buildZipResponse(mockConfigSetService, "_designer_films", "films");

    assertNotNull(response);
    assertEquals(200, response.getStatus());
    final String disposition = response.getHeaderString("Content-Disposition");
    assertTrue(
        "Content-Disposition should use the display name 'films'",
        disposition.contains("films_configset.zip"));
    assertFalse(
        "Content-Disposition must not expose internal _designer_ prefix",
        disposition.contains("_designer_"));
  }
}
