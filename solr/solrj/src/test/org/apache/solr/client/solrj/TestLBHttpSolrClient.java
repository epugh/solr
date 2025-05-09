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
package org.apache.solr.client.solrj;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.impl.LBHttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrResponseBase;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.TimeOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test for LBHttpSolrClient
 *
 * @since solr 1.4
 */
public class TestLBHttpSolrClient extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  SolrInstance[] solr = new SolrInstance[3];
  CloseableHttpClient httpClient;

  // TODO: fix this test to not require FSDirectory
  static String savedFactory;

  @BeforeClass
  public static void beforeClass() {
    savedFactory = System.getProperty("solr.DirectoryFactory");
    System.setProperty("solr.directoryFactory", "org.apache.solr.core.MockFSDirectoryFactory");
    System.setProperty("tests.shardhandler.randomSeed", Long.toString(random().nextLong()));
  }

  @AfterClass
  public static void afterClass() {
    if (savedFactory == null) {
      System.clearProperty("solr.directoryFactory");
    } else {
      System.setProperty("solr.directoryFactory", savedFactory);
    }
    System.clearProperty("tests.shardhandler.randomSeed");
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    httpClient = HttpClientUtil.createClient(null);

    for (int i = 0; i < solr.length; i++) {
      solr[i] = new SolrInstance("solr/collection1" + i, createTempDir("instance-" + i), 0);
      solr[i].setUp();
      solr[i].startJetty();
      addDocs(solr[i]);
    }
  }

  private void addDocs(SolrInstance solrInstance) throws IOException, SolrServerException {
    List<SolrInputDocument> docs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", i);
      doc.addField("name", solrInstance.name);
      docs.add(doc);
    }
    SolrResponseBase resp;
    try (SolrClient client =
        new HttpSolrClient.Builder(solrInstance.getBaseUrl())
            .withDefaultCollection(solrInstance.getDefaultCollection())
            .withHttpClient(httpClient)
            .build()) {
      resp = client.add(docs);
      assertEquals(0, resp.getStatus());
      resp = client.commit();
      assertEquals(0, resp.getStatus());
    }
  }

  @Override
  public void tearDown() throws Exception {
    for (SolrInstance aSolr : solr) {
      if (aSolr != null) {
        aSolr.tearDown();
      }
    }
    HttpClientUtil.close(httpClient);
    super.tearDown();
  }

  public void testSimple() throws Exception {
    String[] solrUrls = new String[solr.length];
    for (int i = 0; i < solr.length; i++) {
      solrUrls[i] = solr[i].getBaseUrl();
    }
    try (LBHttpSolrClient client =
        new LBHttpSolrClient.Builder()
            .withHttpClient(httpClient)
            .withBaseEndpoints(solrUrls)
            .withDefaultCollection(solr[0].getDefaultCollection())
            .setAliveCheckInterval(500)
            .build()) {
      SolrQuery solrQuery = new SolrQuery("*:*");
      Set<String> names = new HashSet<>();
      QueryResponse resp = null;
      for (int i = 0; i < solr.length; i++) {
        resp = client.query(solrQuery);
        assertEquals(10, resp.getResults().getNumFound());
        names.add(resp.getResults().get(0).getFieldValue("name").toString());
      }
      assertEquals(3, names.size());

      // Kill a server and test again
      solr[1].jetty.stop();
      solr[1].jetty = null;
      names.clear();
      for (int i = 0; i < solr.length; i++) {
        resp = client.query(solrQuery);
        assertEquals(10, resp.getResults().getNumFound());
        names.add(resp.getResults().get(0).getFieldValue("name").toString());
      }
      assertEquals(2, names.size());
      assertFalse(names.contains("solr1"));

      // Start the killed server once again
      solr[1].startJetty();
      // Wait for the alive check to complete
      Thread.sleep(1200);
      names.clear();
      for (int i = 0; i < solr.length; i++) {
        resp = client.query(solrQuery);
        assertEquals(10, resp.getResults().getNumFound());
        names.add(resp.getResults().get(0).getFieldValue("name").toString());
      }
      assertEquals(3, names.size());
    }
  }

  public void testTwoServers() throws Exception {
    String[] solrUrls = new String[2];
    for (int i = 0; i < 2; i++) {
      solrUrls[i] = solr[i].getBaseUrl();
    }
    try (LBHttpSolrClient client =
        new LBHttpSolrClient.Builder()
            .withHttpClient(httpClient)
            .withBaseEndpoints(solrUrls)
            .withDefaultCollection(solr[0].getDefaultCollection())
            .setAliveCheckInterval(500)
            .build()) {
      SolrQuery solrQuery = new SolrQuery("*:*");
      QueryResponse resp = null;
      solr[0].jetty.stop();
      solr[0].jetty = null;
      resp = client.query(solrQuery);
      String name = resp.getResults().get(0).getFieldValue("name").toString();
      assertEquals("solr/collection11", name);
      resp = client.query(solrQuery);
      name = resp.getResults().get(0).getFieldValue("name").toString();
      assertEquals("solr/collection11", name);
      solr[1].jetty.stop();
      solr[1].jetty = null;
      solr[0].startJetty();
      Thread.sleep(1200);
      try {
        resp = client.query(solrQuery);
      } catch (SolrServerException e) {
        // try again after a pause in case the error is lack of time to start server
        Thread.sleep(3000);
        resp = client.query(solrQuery);
      }
      name = resp.getResults().get(0).getFieldValue("name").toString();
      assertEquals("solr/collection10", name);
    }
  }

  public void testReliability() throws Exception {
    String[] solrUrls = new String[solr.length];
    for (int i = 0; i < solr.length; i++) {
      solrUrls[i] = solr[i].getBaseUrl();
    }

    try (LBHttpSolrClient client =
        new LBHttpSolrClient.Builder()
            .withHttpClient(httpClient)
            .withBaseEndpoints(solrUrls)
            .withDefaultCollection(solr[0].getDefaultCollection())
            .withConnectionTimeout(500, TimeUnit.MILLISECONDS)
            .withSocketTimeout(500, TimeUnit.MILLISECONDS)
            .setAliveCheckInterval(500)
            .build()) {

      // Kill a server and test again
      solr[1].jetty.stop();
      solr[1].jetty = null;

      // query the servers
      for (String value : solrUrls) client.query(new SolrQuery("*:*"));

      // Start the killed server once again
      solr[1].startJetty();
      // Wait for the alive check to complete
      waitForServer(30, client, 3, solr[1].name);
    }
  }

  // wait maximum ms for serverName to come back up
  private void waitForServer(
      int maxSeconds, LBHttpSolrClient client, int nServers, String serverName) throws Exception {
    final TimeOut timeout = new TimeOut(maxSeconds, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    while (!timeout.hasTimedOut()) {
      QueryResponse resp;
      try {
        resp = client.query(new SolrQuery("*:*"));
      } catch (Exception e) {
        log.warn("", e);
        continue;
      }
      String name = resp.getResults().get(0).getFieldValue("name").toString();
      if (name.equals(serverName)) return;

      Thread.sleep(500);
    }
  }

  private static class SolrInstance {
    String name;
    Path homeDir;
    Path dataDir;
    Path confDir;
    int port;
    JettySolrRunner jetty;

    public SolrInstance(String name, Path homeDir, int port) {
      this.name = name;
      this.homeDir = homeDir;
      this.port = port;

      dataDir = homeDir.resolve("collection1").resolve("data");
      confDir = homeDir.resolve("collection1").resolve("conf");
    }

    public String getHomeDir() {
      return homeDir.toString();
    }

    public String getUrl() {
      return buildUrl(port) + "/collection1";
    }

    public String getBaseUrl() {
      return buildUrl(port);
    }

    public String getDefaultCollection() {
      return "collection1";
    }

    public String getSchemaFile() {
      return "solrj/solr/collection1/conf/schema-replication1.xml";
    }

    public String getConfDir() {
      return confDir.toString();
    }

    public String getDataDir() {
      return dataDir.toString();
    }

    public String getSolrConfigFile() {
      return "solrj/solr/collection1/conf/solrconfig-follower1.xml";
    }

    public String getSolrXmlFile() {
      return "solrj/solr/solr.xml";
    }

    public void setUp() throws Exception {
      Files.createDirectories(homeDir);
      Files.createDirectories(dataDir);
      Files.createDirectories(confDir);

      Files.copy(SolrTestCaseJ4.getFile(getSolrXmlFile()), homeDir.resolve("solr.xml"));

      Path f = confDir.resolve("solrconfig.xml");
      Files.copy(SolrTestCaseJ4.getFile(getSolrConfigFile()), f);
      f = confDir.resolve("schema.xml");
      Files.copy(SolrTestCaseJ4.getFile(getSchemaFile()), f);
      Files.createFile(homeDir.resolve("collection1/core.properties"));
    }

    public void tearDown() throws Exception {
      if (jetty != null) jetty.stop();
      IOUtils.rm(homeDir);
    }

    public void startJetty() throws Exception {

      Properties props = new Properties();
      props.setProperty("solrconfig", "bad_solrconfig.xml");
      props.setProperty("solr.data.dir", getDataDir());

      JettyConfig jettyConfig = JettyConfig.builder().setPort(port).build();

      jetty = new JettySolrRunner(getHomeDir(), props, jettyConfig);
      jetty.start();
      int newPort = jetty.getLocalPort();
      if (port != 0 && newPort != port) {
        fail("TESTING FAILURE: could not grab requested port.");
      }
      this.port = newPort;
      //      System.out.println("waiting.........");
      //      Thread.sleep(5000);
    }
  }
}
