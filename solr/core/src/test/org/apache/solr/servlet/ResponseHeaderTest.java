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
package org.apache.solr.servlet;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.solr.SolrJettyTestBase;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ResponseHeaderTest extends SolrJettyTestBase {

  private static Path solrHomeDirectory;

  @BeforeClass
  public static void beforeTest() throws Exception {
    solrHomeDirectory = createTempDir();
    setupJettyTestHome(solrHomeDirectory, "collection1");
    String top = SolrTestCaseJ4.TEST_HOME() + "/collection1/conf";
    Files.copy(
        Path.of(top, "solrconfig-headers.xml"),
        Path.of(solrHomeDirectory + "/collection1/conf", "solrconfig.xml"),
        StandardCopyOption.REPLACE_EXISTING);
    createAndStartJetty(solrHomeDirectory);
  }

  @AfterClass
  public static void afterTest() throws Exception {
    if (null != solrHomeDirectory) {
      cleanUpJettyHome(solrHomeDirectory);
    }
  }

  @Test
  public void testHttpResponse() throws IOException {
    URI uri = URI.create(getBaseUrl() + "/collection1/withHeaders?q=*:*");
    HttpGet httpGet = new HttpGet(uri);
    HttpResponse response = getHttpClient().execute(httpGet);
    Header[] headers = response.getAllHeaders();
    boolean containsWarningHeader = false;
    for (Header header : headers) {
      if ("Warning".equals(header.getName())) {
        containsWarningHeader = true;
        assertEquals("This is a test warning", header.getValue());
        break;
      }
    }
    assertTrue("Expected header not found", containsWarningHeader);
  }

  public static class ComponentThatAddsHeader extends SearchComponent {

    @Override
    public void prepare(ResponseBuilder rb) {
      rb.rsp.addHttpHeader("Warning", "This is a test warning");
    }

    @Override
    public void process(ResponseBuilder rb) {}

    @Override
    public String getDescription() {
      return null;
    }
  }
}
