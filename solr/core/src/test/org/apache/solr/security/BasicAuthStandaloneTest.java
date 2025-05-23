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
package org.apache.solr.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.solr.cloud.SolrCloudAuthTestCase.NOT_NULL_PREDICATE;
import static org.apache.solr.security.BasicAuthIntegrationTest.STD_CONF;
import static org.apache.solr.security.BasicAuthIntegrationTest.verifySecurityStatus;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.http.message.BasicHeader;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.util.Utils;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.handler.admin.SecurityConfHandler;
import org.apache.solr.handler.admin.SecurityConfHandlerLocalForTesting;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicAuthStandaloneTest extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final Path ROOT_DIR = TEST_HOME();
  private static final Path CONF_DIR =
      ROOT_DIR.resolve("configsets").resolve("configset-2").resolve("conf");

  SecurityConfHandlerLocalForTesting securityConfHandler;
  SolrInstance instance = null;
  JettySolrRunner jetty;

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    instance = new SolrInstance("inst", null);
    instance.setUp();
    jetty = createAndStartJetty(instance);
    securityConfHandler = new SecurityConfHandlerLocalForTesting(jetty.getCoreContainer());
    HttpClientUtil.clearRequestInterceptors(); // Clear out any old Authorization headers
  }

  @Override
  @After
  public void tearDown() throws Exception {
    if (null != jetty) {
      jetty.stop();
      jetty = null;
    }
    super.tearDown();
  }

  @Test
  public void testBasicAuth() throws Exception {

    String authcPrefix = "/admin/authentication";
    String authzPrefix = "/admin/authorization";

    HttpClient httpClient = null;
    SolrClient solrClient = null;
    try {
      httpClient = HttpClientUtil.createClient(null);
      String baseUrl = buildUrl(jetty.getLocalPort());
      solrClient = getHttpSolrClient(baseUrl);

      verifySecurityStatus(httpClient, baseUrl + authcPrefix, "/errorMessages", null, 20);

      // Write security.json locally. Should cause security to be initialized
      securityConfHandler.persistConf(
          new SecurityConfHandler.SecurityConfig()
              .setData(Utils.fromJSONString(STD_CONF.replace("'", "\""))));
      securityConfHandler.securityConfEdited();
      verifySecurityStatus(
          httpClient, baseUrl + authcPrefix, "authentication/class", "solr.BasicAuthPlugin", 20);

      String command = "{\n" + "'set-user': {'harry':'HarryIsCool'}\n" + "}";

      doHttpPost(httpClient, baseUrl + authcPrefix, command, null, null, 401);
      verifySecurityStatus(httpClient, baseUrl + authcPrefix, "authentication.enabled", "true", 20);

      command = "{\n" + "'set-user': {'harry':'HarryIsUberCool'}\n" + "}";

      doHttpPost(httpClient, baseUrl + authcPrefix, command, "solr", "SolrRocks");
      verifySecurityStatus(
          httpClient,
          baseUrl + authcPrefix,
          "authentication/credentials/harry",
          NOT_NULL_PREDICATE,
          20);

      // Read file from SOLR_HOME and verify that it contains our new user
      assertTrue(
          new String(Utils.toJSON(securityConfHandler.getSecurityConfig(false).getData()), UTF_8)
              .contains("harry"));

      // Edit authorization
      verifySecurityStatus(
          httpClient, baseUrl + authzPrefix, "authorization/permissions[1]/role", null, 20);
      doHttpPost(
          httpClient,
          baseUrl + authzPrefix,
          "{'set-permission': {'name': 'update', 'role':'updaterole'}}",
          "solr",
          "SolrRocks");
      command = "{\n" + "'set-permission': {'name': 'read', 'role':'solr'}\n" + "}";
      doHttpPost(httpClient, baseUrl + authzPrefix, command, "solr", "SolrRocks");
      try {
        solrClient.query("collection1", new MapSolrParams(Collections.singletonMap("q", "foo")));
        fail("Should return a 401 response");
      } catch (Exception e) {
        // Test that the second doPost request to /security/authorization went through
        verifySecurityStatus(
            httpClient, baseUrl + authzPrefix, "authorization/permissions[2]/role", "solr", 20);
      }
    } finally {
      if (httpClient != null) {
        HttpClientUtil.close(httpClient);
        solrClient.close();
      }
    }
  }

  static void doHttpPost(
      HttpClient cl, String url, String jsonCommand, String basicUser, String basicPass)
      throws IOException {
    doHttpPost(cl, url, jsonCommand, basicUser, basicPass, 200);
  }

  static void doHttpPost(
      HttpClient cl,
      String url,
      String jsonCommand,
      String basicUser,
      String basicPass,
      int expectStatusCode)
      throws IOException {
    doHttpPostWithHeader(
        cl, url, jsonCommand, getBasicAuthHeader(basicUser, basicPass), expectStatusCode);
  }

  static void doHttpPostWithHeader(
      HttpClient cl, String url, String jsonCommand, Header header, int expectStatusCode)
      throws IOException {
    HttpPost httpPost = new HttpPost(url);
    httpPost.setHeader(header);
    httpPost.setEntity(new ByteArrayEntity(jsonCommand.replace("'", "\"").getBytes(UTF_8)));
    httpPost.addHeader("Content-Type", "application/json; charset=UTF-8");
    HttpResponse r = cl.execute(httpPost);
    int statusCode = r.getStatusLine().getStatusCode();
    Utils.consumeFully(r.getEntity());
    assertEquals("proper_cred sent, but access denied", expectStatusCode, statusCode);
  }

  private static Header getBasicAuthHeader(String user, String pwd) {
    String userPass = user + ":" + pwd;
    String encoded = Base64.getEncoder().encodeToString(userPass.getBytes(UTF_8));
    return new BasicHeader("Authorization", "Basic " + encoded);
  }

  public static void setBasicAuthHeader(AbstractHttpMessage httpMsg, String user, String pwd) {
    final Header basicAuthHeader = getBasicAuthHeader(user, pwd);
    httpMsg.setHeader(basicAuthHeader);
    if (log.isInfoEnabled()) {
      log.info("Added Basic Auth security Header {}", basicAuthHeader.getValue());
    }
  }

  static JettySolrRunner createAndStartJetty(SolrInstance instance) throws Exception {
    Properties nodeProperties = new Properties();
    nodeProperties.setProperty("solr.data.dir", instance.getDataDir().toString());
    JettySolrRunner jetty =
        new JettySolrRunner(
            instance.getHomeDir().toString(), nodeProperties, JettyConfig.builder().build());
    jetty.start();
    return jetty;
  }

  static class SolrInstance {
    String name;
    Integer port;
    Path homeDir;
    Path confDir;
    Path dataDir;

    /**
     * if leaderPort is null, this instance is a leader -- otherwise this instance is a follower,
     * and assumes the leader is on localhost at the specified port.
     */
    public SolrInstance(String name, Integer port) {
      this.name = name;
      this.port = port;
    }

    public Path getHomeDir() {
      return homeDir;
    }

    public Path getSchemaFile() {
      return CONF_DIR.resolve("schema.xml");
    }

    public Path getDataDir() {
      return dataDir;
    }

    public Path getSolrConfigFile() {
      return CONF_DIR.resolve("solrconfig.xml");
    }

    public Path getSolrXmlFile() {
      return ROOT_DIR.resolve("solr.xml");
    }

    public void setUp() throws Exception {
      homeDir = createTempDir(name).toAbsolutePath();
      dataDir = homeDir.resolve("collection1").resolve("data");
      confDir = homeDir.resolve("collection1").resolve("conf");

      Files.createDirectories(homeDir);
      Files.createDirectories(dataDir);
      Files.createDirectories(confDir);

      Files.copy(getSolrXmlFile(), homeDir.resolve("solr.xml"));
      Files.copy(getSolrConfigFile(), confDir.resolve("solrconfig.xml"));
      Files.copy(getSchemaFile(), confDir.resolve("schema.xml"));

      Files.createFile(homeDir.resolve("collection1").resolve("core.properties"));
    }
  }
}
