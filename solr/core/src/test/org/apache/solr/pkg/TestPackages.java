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

package org.apache.solr.pkg;

import static org.apache.solr.common.cloud.ZkStateReader.SOLR_PKGS_PATH;
import static org.apache.solr.common.params.CommonParams.JAVABIN;
import static org.apache.solr.common.params.CommonParams.WT;
import static org.apache.solr.core.TestSolrConfigHandler.getFileContent;
import static org.apache.solr.filestore.TestDistribFileStore.checkAllNodesForFile;
import static org.apache.solr.filestore.TestDistribFileStore.readFile;
import static org.apache.solr.filestore.TestDistribFileStore.uploadKey;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.lucene.analysis.core.WhitespaceTokenizerFactory;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilterFactory;
import org.apache.lucene.util.ResourceLoader;
import org.apache.lucene.util.ResourceLoaderAware;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.BaseHttpSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.RequestWriter;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.client.solrj.request.beans.PackagePayload;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.NavigableObject;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.annotation.JsonProperty;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ReflectMapWriter;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.SolrCore;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.filestore.ClusterFileStore;
import org.apache.solr.filestore.TestDistribFileStore;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.util.LogLevel;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@LogLevel("org.apache.solr.pkg.PackageLoader=DEBUG;org.apache.solr.pkg.PackageAPI=DEBUG")
public class TestPackages extends SolrCloudTestCase {

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty("enable.packages", "true");
    configureCluster(4)
        .withJettyConfig(jetty -> jetty.enableV2(true))
        .addConfig("conf", configset("conf3"))
        .addConfig("conf1", configset("schema-package"))
        .configure();
  }

  @After
  @Override
  public void tearDown() throws Exception {
    if (cluster != null) {
      cluster.shutdown();
    }
    System.clearProperty("enable.packages");

    super.tearDown();
  }

  public static class ConfigPlugin implements ReflectMapWriter {
    @JsonProperty public String name;

    @JsonProperty("class")
    public String klass;
  }

  @Test
  public void testCoreReloadingPlugin() throws Exception {
    String FILE1 = "/mypkg/runtimelibs.jar";
    String COLLECTION_NAME = "testCoreReloadingPluginColl";
    byte[] derFile = readFile("cryptokeys/pub_key512.der");
    uploadKey(derFile, ClusterFileStore.KEYS_DIR + "/pub_key512.der", cluster);
    postFileAndWait(
        cluster,
        "runtimecode/runtimelibs.jar.bin",
        FILE1,
        "L3q/qIGs4NaF6JiO0ZkMUFa88j0OmYc+I6O7BOdNuMct/xoZ4h73aZHZGc0+nmI1f/U3bOlMPINlSOM6LK3JpQ==");

    PackagePayload.AddVersion add = new PackagePayload.AddVersion();
    add.version = "1.0";
    add.pkg = "mypkg";
    add.files = Arrays.asList(new String[] {FILE1});
    V2Request req =
        new V2Request.Builder("/cluster/package")
            .forceV2(true)
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(Collections.singletonMap("add", add))
            .build();

    req.process(cluster.getSolrClient());
    TestDistribFileStore.assertResponseValues(
        10,
        () ->
            new V2Request.Builder("/cluster/package")
                .withMethod(SolrRequest.METHOD.GET)
                .build()
                .process(cluster.getSolrClient()),
        Map.of(
            ":result:packages:mypkg[0]:version",
            "1.0",
            ":result:packages:mypkg[0]:files[0]",
            FILE1));

    CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf", 2, 2)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION_NAME, 2, 4);

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "query", "filterCache", add.pkg, add.version);

    add.version = "2.0";
    req.process(cluster.getSolrClient());
    TestDistribFileStore.assertResponseValues(
        10,
        () ->
            new V2Request.Builder("/cluster/package")
                .withMethod(SolrRequest.METHOD.GET)
                .build()
                .process(cluster.getSolrClient()),
        Map.of(
            ":result:packages:mypkg[1]:version",
            "2.0",
            ":result:packages:mypkg[1]:files[0]",
            FILE1));
    new UpdateRequest().commit(cluster.getSolrClient(), COLLECTION_NAME);

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "query", "filterCache", "mypkg", "2.0");
  }

  @Test
  public void testPluginLoading() throws Exception {
    String FILE1 = "/mypkg/runtimelibs.jar";
    String FILE2 = "/mypkg/runtimelibs_v2.jar";
    String FILE3 = "/mypkg/runtimelibs_v3.jar";
    String URP1 = "/mypkg/testurpv1.jar";
    String URP2 = "/mypkg/testurpv2.jar";
    String EXPR1 = "/mypkg/expressible.jar";
    String COLLECTION_NAME = "testPluginLoadingColl";
    byte[] derFile = readFile("cryptokeys/pub_key512.der");
    uploadKey(derFile, ClusterFileStore.KEYS_DIR + "/pub_key512.der", cluster);
    postFileAndWait(
        cluster,
        "runtimecode/runtimelibs.jar.bin",
        FILE1,
        "L3q/qIGs4NaF6JiO0ZkMUFa88j0OmYc+I6O7BOdNuMct/xoZ4h73aZHZGc0+nmI1f/U3bOlMPINlSOM6LK3JpQ==");

    postFileAndWait(
        cluster,
        "runtimecode/testurp_v1.jar.bin",
        URP1,
        "h6UmMzuPqu4hQFGLBMJh/6kDSEXpJlgLsQDXx0KuxXWkV5giilRP57K3towiJRh2J+rqihqIghNCi3YgzgUnWQ==");

    postFileAndWait(
        cluster,
        "runtimecode/expressible.jar.bin",
        EXPR1,
        "ZOT11arAiPmPZYOHzqodiNnxO9pRyRozWZEBX8XGjU1/HJptFnZK+DI7eXnUtbNaMcbXE2Ze8hh4M/eGyhY8BQ==");

    PackagePayload.AddVersion add = new PackagePayload.AddVersion();
    add.version = "1.0";
    add.pkg = "mypkg";
    add.files = Arrays.asList(new String[] {FILE1, URP1, EXPR1});
    V2Request req =
        new V2Request.Builder("/cluster/package")
            .forceV2(true)
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(Collections.singletonMap("add", add))
            .build();

    req.process(cluster.getSolrClient());

    CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf", 2, 2)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION_NAME, 2, 4);

    TestDistribFileStore.assertResponseValues(
        10,
        () ->
            new V2Request.Builder("/cluster/package")
                .withMethod(SolrRequest.METHOD.GET)
                .build()
                .process(cluster.getSolrClient()),
        Map.of(
            ":result:packages:mypkg[0]:version",
            "1.0",
            ":result:packages:mypkg[0]:files[0]",
            FILE1));
    Map<String, ConfigPlugin> plugins = new LinkedHashMap<>();
    ConfigPlugin p = new ConfigPlugin();
    p.klass = "mypkg:org.apache.solr.core.RuntimeLibReqHandler";
    p.name = "/runtime";
    plugins.put("create-requesthandler", p);

    p = new ConfigPlugin();
    p.klass = "mypkg:org.apache.solr.core.RuntimeLibSearchComponent";
    p.name = "get";
    plugins.put("create-searchcomponent", p);

    p = new ConfigPlugin();
    p.klass = "mypkg:org.apache.solr.core.RuntimeLibResponseWriter";
    p.name = "json1";
    plugins.put("create-queryResponseWriter", p);

    p = new ConfigPlugin();
    p.klass = "mypkg:org.apache.solr.update.TestVersionedURP";
    p.name = "myurp";
    plugins.put("create-updateProcessor", p);

    p = new ConfigPlugin();
    p.klass = "mypkg:org.apache.solr.client.solrj.io.stream.metrics.MinCopyMetric";
    p.name = "mincopy";
    plugins.put("create-expressible", p);

    V2Request v2r =
        new V2Request.Builder("/c/" + COLLECTION_NAME + "/config")
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(plugins)
            .forceV2(true)
            .build();
    cluster.getSolrClient().request(v2r);

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "queryResponseWriter", "json1", "mypkg", "1.0");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "searchComponent", "get", "mypkg", "1.0");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "requestHandler", "/runtime", "mypkg", "1.0");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "updateProcessor", "myurp", "mypkg", "1.0");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "expressible", "mincopy", "mypkg", "1.0");

    TestDistribFileStore.assertResponseValues(
        10,
        cluster.getSolrClient(),
        new GenericSolrRequest(
                SolrRequest.METHOD.GET,
                "/stream",
                new MapSolrParams(
                    Map.of("collection", COLLECTION_NAME, WT, JAVABIN, "action", "plugins")))
            .setRequiresCollection(true),
        Map.of(":plugins:mincopy", "org.apache.solr.client.solrj.io.stream.metrics.MinCopyMetric"));

    UpdateRequest ur = new UpdateRequest();
    ur.add(new SolrInputDocument("id", "1"));
    ur.setParam("processor", "myurp");
    ur.process(cluster.getSolrClient(), COLLECTION_NAME);
    cluster.getSolrClient().commit(COLLECTION_NAME, true, true);

    QueryResponse result = cluster.getSolrClient().query(COLLECTION_NAME, new SolrQuery("id:1"));

    assertEquals("Version 1", result.getResults().get(0).getFieldValue("TestVersionedURP.Ver_s"));

    executeReq(
        "/" + COLLECTION_NAME + "/runtime?wt=javabin",
        cluster.getRandomJetty(random()),
        Utils.JAVABINCONSUMER,
        Map.of("class", "org.apache.solr.core.RuntimeLibReqHandler"));

    executeReq(
        "/" + COLLECTION_NAME + "/get?wt=json",
        cluster.getRandomJetty(random()),
        Utils.JSONCONSUMER,
        Map.of("Version", "1"));

    executeReq(
        "/" + COLLECTION_NAME + "/runtime?wt=json1",
        cluster.getRandomJetty(random()),
        Utils.JSONCONSUMER,
        Map.of("wt", "org.apache.solr.core.RuntimeLibResponseWriter"));

    // now upload the second jar
    postFileAndWait(
        cluster,
        "runtimecode/runtimelibs_v2.jar.bin",
        FILE2,
        "j+Rflxi64tXdqosIhbusqi6GTwZq8znunC/dzwcWW0/dHlFGKDurOaE1Nz9FSPJuXbHkVLj638yZ0Lp1ssnoYA==");

    postFileAndWait(
        cluster,
        "runtimecode/testurp_v2.jar.bin",
        URP2,
        "P/ptFXRvQMd4oKPvadSpd+A9ffwY3gcex5GVFVRy3df0/OF8XT5my8rQz7FZva+2ORbWxdXS8NKwNrbPVHLGXw==");
    // add the version using package API
    add.version = "1.1";
    add.files = Arrays.asList(new String[] {FILE2, URP2, EXPR1});
    req.process(cluster.getSolrClient());

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "queryResponseWriter", "json1", "mypkg", "1.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "searchComponent", "get", "mypkg", "1.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "requestHandler", "/runtime", "mypkg", "1.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "updateProcessor", "myurp", "mypkg", "1.1");

    executeReq(
        "/" + COLLECTION_NAME + "/get?wt=json",
        cluster.getRandomJetty(random()),
        Utils.JSONCONSUMER,
        Map.of("Version", "2"));

    // now upload the third jar
    postFileAndWait(
        cluster,
        "runtimecode/runtimelibs_v3.jar.bin",
        FILE3,
        "a400n4T7FT+2gM0SC6+MfSOExjud8MkhTSFylhvwNjtWwUgKdPFn434Wv7Qc4QEqDVLhQoL3WqYtQmLPti0G4Q==");

    add.version = "2.1";
    add.files = Arrays.asList(new String[] {FILE3, URP2, EXPR1});
    req.process(cluster.getSolrClient());

    // now let's verify that the classes are updated
    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "queryResponseWriter", "json1", "mypkg", "2.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "searchComponent", "get", "mypkg", "2.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "requestHandler", "/runtime", "mypkg", "2.1");

    executeReq(
        "/" + COLLECTION_NAME + "/runtime?wt=json",
        cluster.getRandomJetty(random()),
        Utils.JSONCONSUMER,
        Map.of("Version", "2"));

    // insert a doc with urp
    ur = new UpdateRequest();
    ur.add(new SolrInputDocument("id", "2"));
    ur.setParam("processor", "myurp");
    ur.process(cluster.getSolrClient(), COLLECTION_NAME);
    cluster.getSolrClient().commit(COLLECTION_NAME, true, true);

    result = cluster.getSolrClient().query(COLLECTION_NAME, new SolrQuery("id:2"));

    assertEquals("Version 2", result.getResults().get(0).getFieldValue("TestVersionedURP.Ver_s"));

    PackagePayload.DelVersion delVersion = new PackagePayload.DelVersion();
    delVersion.pkg = "mypkg";
    delVersion.version = "1.0";
    V2Request delete =
        new V2Request.Builder("/cluster/package")
            .withMethod(SolrRequest.METHOD.POST)
            .forceV2(true)
            .withPayload(Collections.singletonMap("delete", delVersion))
            .build();
    delete.process(cluster.getSolrClient());

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "queryResponseWriter", "json1", "mypkg", "2.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "searchComponent", "get", "mypkg", "2.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "requestHandler", "/runtime", "mypkg", "2.1");

    // now remove the hughest version. So, it will roll back to the next highest one
    delVersion.version = "2.1";
    delete.process(cluster.getSolrClient());

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "queryResponseWriter", "json1", "mypkg", "1.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "searchComponent", "get", "mypkg", "1.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "requestHandler", "/runtime", "mypkg", "1.1");

    ModifiableSolrParams params = new ModifiableSolrParams();
    params.add("collection", COLLECTION_NAME);
    new GenericSolrRequest(SolrRequest.METHOD.POST, "/config/params", params) {
      @Override
      public RequestWriter.ContentWriter getContentWriter(String expectedType) {
        return new RequestWriter.StringPayloadContentWriter(
            "{set:{PKG_VERSIONS:{mypkg : '1.1'}}}", ClientUtils.TEXT_JSON);
      }

      @Override
      public boolean requiresCollection() {
        return true;
      }
    }.process(cluster.getSolrClient());

    add.version = "2.1";
    add.files = Arrays.asList(new String[] {FILE3, URP2, EXPR1});
    req.process(cluster.getSolrClient());

    // the collections mypkg is set to use version 1.1
    // so no upgrade

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "queryResponseWriter", "json1", "mypkg", "1.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "searchComponent", "get", "mypkg", "1.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "requestHandler", "/runtime", "mypkg", "1.1");

    new GenericSolrRequest(SolrRequest.METHOD.POST, "/config/params", params) {
      @Override
      public RequestWriter.ContentWriter getContentWriter(String expectedType) {
        return new RequestWriter.StringPayloadContentWriter(
            "{set:{PKG_VERSIONS:{mypkg : '2.1'}}}", ClientUtils.TEXT_JSON);
      }

      @Override
      public boolean requiresCollection() {
        return true;
      }
    }.process(cluster.getSolrClient());

    // now, let's force every collection using 'mypkg' to refresh
    // so that it uses version 2.1
    new V2Request.Builder("/cluster/package")
        .withMethod(SolrRequest.METHOD.POST)
        .withPayload("{refresh : mypkg}")
        .forceV2(true)
        .build()
        .process(cluster.getSolrClient());

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "queryResponseWriter", "json1", "mypkg", "2.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "searchComponent", "get", "mypkg", "2.1");

    verifyComponent(
        cluster.getSolrClient(), COLLECTION_NAME, "requestHandler", "/runtime", "mypkg", "2.1");

    plugins.clear();
    p = new ConfigPlugin();
    p.name = "/rt_2";
    p.klass = "mypkg:" + C.class.getName();
    plugins.put("create-requesthandler", p);

    p = new ConfigPlugin();
    p.name = "qp1";
    p.klass = "mypkg:" + C2.class.getName();
    plugins.put("create-queryparser", p);

    v2r =
        new V2Request.Builder("/c/" + COLLECTION_NAME + "/config")
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(plugins)
            .forceV2(true)
            .build();
    cluster.getSolrClient().request(v2r);
    assertTrue(C.informCalled);
    assertTrue(C2.informCalled);

    // we create a new node. This node does not have the packages. But it should download it from
    // another node
    JettySolrRunner jetty = cluster.startJettySolrRunner();
    // create a new replica for this collection. it should end up
    CollectionAdminRequest.addReplicaToShard(COLLECTION_NAME, "shard1")
        .setNrtReplicas(1)
        .setNode(jetty.getNodeName())
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION_NAME, 2, 5);
    checkAllNodesForFile(
        cluster, FILE3, Map.of(":files:" + FILE3 + ":name", "runtimelibs_v3.jar"), false);
  }

  @SuppressWarnings("unchecked")
  private void executeReq(
      String uri,
      JettySolrRunner jetty,
      Utils.InputStreamConsumer<?> parser,
      Map<String, Object> expected)
      throws Exception {
    try (HttpSolrClient client = (HttpSolrClient) jetty.newClient()) {
      TestDistribFileStore.assertResponseValues(
          10,
          () ->
              NavigableObject.wrap(
                  Utils.executeGET(client.getHttpClient(), jetty.getBaseUrl() + uri, parser)),
          expected);
    }
  }

  private void verifyComponent(
      SolrClient client,
      String COLLECTION_NAME,
      String componentType,
      String componentName,
      String pkg,
      String version)
      throws Exception {
    SolrParams params =
        new MapSolrParams(
            Map.of(
                "collection",
                COLLECTION_NAME,
                WT,
                JAVABIN,
                "componentName",
                componentName,
                "meta",
                "true"));

    GenericSolrRequest req1 =
        new GenericSolrRequest(SolrRequest.METHOD.GET, "/config/" + componentType, params)
            .setRequiresCollection(true);
    TestDistribFileStore.assertResponseValues(
        10,
        client,
        req1,
        Map.of(
            ":config:" + componentType + ":" + componentName + ":_packageinfo_:package", pkg,
            ":config:" + componentType + ":" + componentName + ":_packageinfo_:version", version));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAPI() throws Exception {
    String errPath = "/error/details[0]/errorMessages[0]";
    String FILE1 = "/mypkg/v.0.12/jar_a.jar";
    String FILE2 = "/mypkg/v.0.12/jar_b.jar";
    String FILE3 = "/mypkg/v.0.13/jar_a.jar";

    PackagePayload.AddVersion add = new PackagePayload.AddVersion();
    add.version = "0.12";
    add.pkg = "test_pkg";
    add.files = List.of(FILE1, FILE2);
    V2Request req =
        new V2Request.Builder("/cluster/package")
            .forceV2(true)
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(Collections.singletonMap("add", add))
            .build();

    // the files are not yet there. The command should fail with error saying "No such file"
    expectError(req, cluster.getSolrClient(), errPath, "No such file:");

    // post the jar file. No signature is sent
    postFileAndWait(cluster, "runtimecode/runtimelibs.jar.bin", FILE1, null);

    add.files = Collections.singletonList(FILE1);
    expectError(req, cluster.getSolrClient(), errPath, FILE1 + " has no signature");
    // now we upload the keys
    byte[] derFile = readFile("cryptokeys/pub_key512.der");
    uploadKey(derFile, ClusterFileStore.KEYS_DIR + "/pub_key512.der", cluster);
    // and upload the same file with a different name, but it has proper signature
    postFileAndWait(
        cluster,
        "runtimecode/runtimelibs.jar.bin",
        FILE2,
        "L3q/qIGs4NaF6JiO0ZkMUFa88j0OmYc+I6O7BOdNuMct/xoZ4h73aZHZGc0+nmI1f/U3bOlMPINlSOM6LK3JpQ==");
    // with correct signature
    // after uploading the file, let's delete the keys to see if we get proper error message
    add.files = Collections.singletonList(FILE2);
    /*expectError(req, cluster.getSolrClient(), errPath,
    "ZooKeeper does not have any public keys");*/

    // Now lets' put the keys back

    // this time we have a file with proper signature, public keys are in ZK
    // so the add {} command should succeed
    req.process(cluster.getSolrClient());

    // Now verify the data in ZK
    TestDistribFileStore.assertResponseValues(
        1,
        () ->
            NavigableObject.wrap(
                Utils.fromJSON(
                    cluster.getZkClient().getData(SOLR_PKGS_PATH, null, new Stat(), true))),
        Map.of(":packages:test_pkg[0]:version", "0.12", ":packages:test_pkg[0]:files[0]", FILE2));

    // post a new jar with a proper signature
    postFileAndWait(
        cluster,
        "runtimecode/runtimelibs_v2.jar.bin",
        FILE3,
        "j+Rflxi64tXdqosIhbusqi6GTwZq8znunC/dzwcWW0/dHlFGKDurOaE1Nz9FSPJuXbHkVLj638yZ0Lp1ssnoYA==");

    // this time we are adding the second version of the package (0.13)
    add.version = "0.13";
    add.pkg = "test_pkg";
    add.files = Collections.singletonList(FILE3);

    // this request should succeed
    req.process(cluster.getSolrClient());
    // no verify the data (/packages.json) in ZK
    TestDistribFileStore.assertResponseValues(
        1,
        () ->
            NavigableObject.wrap(
                Utils.fromJSON(
                    cluster.getZkClient().getData(SOLR_PKGS_PATH, null, new Stat(), true))),
        Map.of(":packages:test_pkg[1]:version", "0.13", ":packages:test_pkg[1]:files[0]", FILE3));

    // Now we will just delete one version
    PackagePayload.DelVersion delVersion = new PackagePayload.DelVersion();
    delVersion.version = "0.1"; // this version does not exist
    delVersion.pkg = "test_pkg";
    req =
        new V2Request.Builder("/cluster/package")
            .forceV2(true)
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(Collections.singletonMap("delete", delVersion))
            .build();

    // we are expecting an error
    expectError(req, cluster.getSolrClient(), errPath, "No such version:");

    delVersion.version = "0.12"; // correct version. Should succeed
    req.process(cluster.getSolrClient());
    // Verify with ZK that the data is correct
    TestDistribFileStore.assertResponseValues(
        1,
        () ->
            NavigableObject.wrap(
                Utils.fromJSON(
                    cluster.getZkClient().getData(SOLR_PKGS_PATH, null, new Stat(), true))),
        Map.of(":packages:test_pkg[0]:version", "0.13", ":packages:test_pkg[0]:files[0]", FILE3));

    // So far we have been verifying the details with  ZK directly
    // use the package read API to verify with each node that it has the correct data
    for (JettySolrRunner jetty : cluster.getJettySolrRunners()) {
      String path = jetty.getBaseURLV2().toString() + "/cluster/package?wt=javabin";
      TestDistribFileStore.assertResponseValues(
          10,
          new Callable<NavigableObject>() {
            @Override
            public NavigableObject call() throws Exception {
              try (HttpSolrClient solrClient = (HttpSolrClient) jetty.newClient()) {
                return (NavigableObject)
                    Utils.executeGET(solrClient.getHttpClient(), path, Utils.JAVABINCONSUMER);
              }
            }
          },
          Map.of(
              ":result:packages:test_pkg[0]:version",
              "0.13",
              ":result:packages:test_pkg[0]:files[0]",
              FILE3));
    }
  }

  public static class C extends RequestHandlerBase implements SolrCoreAware {
    static boolean informCalled = false;

    @Override
    public void inform(SolrCore core) {
      informCalled = true;
    }

    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) {}

    @Override
    public String getDescription() {
      return "test";
    }

    @Override
    public Name getPermissionName(AuthorizationContext request) {
      return Name.ALL;
    }
  }

  public static class C2 extends QParserPlugin implements ResourceLoaderAware {
    static boolean informCalled = false;

    @Override
    public void inform(ResourceLoader loader) {
      informCalled = true;
    }

    @Override
    public QParser createParser(
        String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
      return null;
    }
  }

  @Test
  public void testSchemaPlugins() throws Exception {
    String COLLECTION_NAME = "testSchemaLoadingColl";
    System.setProperty("managed.schema.mutable", "true");

    IndexSchema[] schemas = new IndexSchema[2]; // tracks schemas for a selected core

    String FILE1 = "/schemapkg/schema-plugins.jar";
    byte[] derFile = readFile("cryptokeys/pub_key512.der");
    uploadKey(derFile, ClusterFileStore.KEYS_DIR + "/pub_key512.der", cluster);
    postFileAndWait(
        cluster,
        "runtimecode/schema-plugins.jar.bin",
        FILE1,
        "U+AdO/jgY3DtMpeFRGoTQk72iA5g/qjPvdQYPGBaXB5+ggcTZk4FoIWiueB0bwGJ8Mg3V/elxOqEbD2JR8R0tA==");

    String FILE2 = "/schemapkg/payload-component.jar";
    postFileAndWait(
        cluster,
        "runtimecode/payload-component.jar.bin",
        FILE2,
        "gI6vYUDmSXSXmpNEeK1cwqrp4qTeVQgizGQkd8A4Prx2K8k7c5QlXbcs4lxFAAbbdXz9F4esBqTCiLMjVDHJ5Q==");

    // upload package v1.0
    PackagePayload.AddVersion add = new PackagePayload.AddVersion();
    add.version = "1.0";
    add.pkg = "schemapkg";
    add.files = Arrays.asList(FILE1, FILE2);
    V2Request req =
        new V2Request.Builder("/cluster/package")
            .forceV2(true)
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(Collections.singletonMap("add", add))
            .build();
    req.process(cluster.getSolrClient());

    TestDistribFileStore.assertResponseValues(
        10,
        () ->
            new V2Request.Builder("/cluster/package")
                .withMethod(SolrRequest.METHOD.GET)
                .build()
                .process(cluster.getSolrClient()),
        Map.of(
            ":result:packages:schemapkg[0]:version",
            "1.0",
            ":result:packages:schemapkg[0]:files[0]",
            FILE1));

    CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", 2, 2)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION_NAME, 2, 4);

    // make note of the schema instance for one of the cores
    SolrCore.Provider coreProvider =
        cluster.getJettySolrRunners().stream()
            .flatMap(
                jetty ->
                    jetty.getCoreContainer().getAllCoreNames().stream()
                        .map(name -> new SolrCore.Provider(jetty.getCoreContainer(), name, null)))
            .findFirst()
            .orElseThrow();

    coreProvider.withCore(core -> schemas[0] = core.getLatestSchema());

    // upload package v2.0
    add = new PackagePayload.AddVersion();
    add.version = "2.0";
    add.pkg = "schemapkg";
    add.files = Arrays.asList(FILE1, FILE2);
    req =
        new V2Request.Builder("/cluster/package")
            .forceV2(true)
            .withMethod(SolrRequest.METHOD.POST)
            .withPayload(Collections.singletonMap("add", add))
            .build();
    req.process(cluster.getSolrClient());

    TestDistribFileStore.assertResponseValues(
        10,
        () ->
            new V2Request.Builder("/cluster/package")
                .withMethod(SolrRequest.METHOD.GET)
                .build()
                .process(cluster.getSolrClient()),
        Map.of(
            ":result:packages:schemapkg[1]:version",
            "2.0",
            ":result:packages:schemapkg[1]:files[0]",
            FILE1));

    // even though package version 2.0 uses exactly the same files
    // as version 1.0, the core schema should still reload, and
    // the core should be associated with a different schema instance
    TestDistribFileStore.assertResponseValues(
        10,
        () -> {
          coreProvider.withCore(core -> schemas[1] = core.getLatestSchema());
          return params("schemaReloaded", (schemas[0] != schemas[1]) ? "yes" : "no");
        },
        Map.of("schemaReloaded", "yes"));

    // after the reload, the custom field type class now comes from package v2.0
    String fieldTypeName = "myNewTextFieldWithAnalyzerClass";

    FieldType fieldTypeV1 = schemas[0].getFieldTypeByName(fieldTypeName);
    assertEquals("my.pkg.MyTextField", fieldTypeV1.getClass().getCanonicalName());

    FieldType fieldTypeV2 = schemas[1].getFieldTypeByName(fieldTypeName);
    assertEquals("my.pkg.MyTextField", fieldTypeV2.getClass().getCanonicalName());

    assertNotEquals(
        "my.pkg.MyTextField classes should be from different classloaders",
        fieldTypeV1.getClass(),
        fieldTypeV2.getClass());
  }

  public static void postFileAndWait(
      MiniSolrCloudCluster cluster, String fname, String path, String sig) throws Exception {
    ByteBuffer fileContent = getFileContent(fname);
    @SuppressWarnings("ByteBufferBackingArray") // this is the result of a call to wrap()
    String sha512 = DigestUtils.sha512Hex(fileContent.array());

    TestDistribFileStore.postFile(
        cluster.getSolrClient(), fileContent, path, sig); // has file, but no signature

    TestDistribFileStore.checkAllNodesForFile(
        cluster, path, Map.of(":files:" + path + ":sha512", sha512), false);
  }

  private void expectError(V2Request req, SolrClient client, String errPath, String expectErrorMsg)
      throws IOException, SolrServerException {
    try {
      req.process(client);
      fail("should have failed with message : " + expectErrorMsg);
    } catch (BaseHttpSolrClient.RemoteExecutionException e) {
      String msg = Objects.requireNonNullElse(e.getMetaData()._getStr(errPath), "");
      assertTrue(
          "should have failed with message: " + expectErrorMsg + "actual message : " + msg,
          msg.contains(expectErrorMsg));
    }
  }

  public static class BasePatternReplaceCharFilterFactory extends PatternReplaceCharFilterFactory {
    public BasePatternReplaceCharFilterFactory(Map<String, String> args) {
      super(args);
    }
  }

  public static class BaseWhitespaceTokenizerFactory extends WhitespaceTokenizerFactory {

    public BaseWhitespaceTokenizerFactory(Map<String, String> args) {
      super(args);
    }
  }

  /*
  //copy the jav files to a package and then run the main method
  public static void main(String[] args) throws Exception {
    persistZip("/tmp/x.jar", MyPatternReplaceCharFilterFactory.class, MyTextField.class, MyWhitespaceTokenizerFactory.class);
  }*/

  public static ByteBuffer persistZip(String loc, Class<?>... classes) throws IOException {
    ByteBuffer jar = generateZip(classes);
    try (FileOutputStream fos = new FileOutputStream(loc)) {
      fos.write(jar.array(), jar.arrayOffset(), jar.limit());
      fos.flush();
    }
    return jar;
  }

  public static ByteBuffer generateZip(Class<?>... classes) throws IOException {
    Utils.BAOS bos = new Utils.BAOS();
    try (ZipOutputStream zipOut = new ZipOutputStream(bos)) {
      zipOut.setLevel(ZipOutputStream.DEFLATED);
      for (Class<?> c : classes) {
        String path = c.getName().replace('.', '/').concat(".class");
        ZipEntry entry = new ZipEntry(path);
        ByteBuffer b = Utils.toByteArray(c.getClassLoader().getResourceAsStream(path));
        zipOut.putNextEntry(entry);
        zipOut.write(b.array(), b.arrayOffset(), b.limit());
        zipOut.closeEntry();
      }
    }
    return bos.getByteBuffer();
  }
}
