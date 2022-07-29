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
package org.apache.solr.handler;

import org.apache.solr.SolrTestCaseJ4;

import org.apache.solr.client.solrj.request.SolrPing;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;

import java.util.Map;

import static org.apache.solr.common.util.Utils.fromJSONString;

public class ISpyRequestHandlerTest extends SolrTestCaseJ4 {
  protected int NUM_SERVERS = 5;
  protected int NUM_SHARDS = 2;
  protected int REPLICATION_FACTOR = 2;

  private ISpyRequestHandler handler = null;

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");
  }

  @Before
  public void before() throws IOException {
    // by default, use relative file in dataDir

    handler = new ISpyRequestHandler();
    NamedList<String> initParams = new NamedList<>();
    //initParams.add(PingRequestHandler.HEALTHCHECK_FILE_PARAM, fileNameParam);
    handler.init(initParams);
    handler.inform(h.getCore());
  }

  public void testISpyWithNoCache() throws Exception {

  }

  public void testListingAllISpy() throws Exception {

  }


  public void testLookingUpOneISpy() throws Exception {
    assertU(adoc("id", "10", "title", "test", "val_s1", "aaa"));
    assertU(adoc("id", "11", "title", "test", "val_s1", "bbb"));
    assertU(adoc("id", "12", "title", "test", "val_s1", "ccc"));
    assertU(commit());

    assertQ(req("q", "title:test"), "//*[@numFound='3']");

    String response =
            JQTee(req("q", "title:test","indent", "true"));
    Map<?, ?> res = (Map<?, ?>) fromJSONString(response);
    Map<?, ?> body = (Map<?, ?>) (res.get("response"));
    assertTrue("Should have 3 docs", (long) (body.get("numFound")) == 3);

    SolrQueryResponse rsp = null;
    rsp = makeRequest(handler, req("action", "spy", "q", "title:test"));

    System.out.print(rsp.getValues());

    assertEquals("disabled", rsp.getValues().get("spy"));

  }


  public void testGettingStatus() throws Exception {
    SolrQueryResponse rsp = null;

    handler.handleEnable(true);

    rsp = makeRequest(handler, req("action", "status"));

    assertEquals("enabled", rsp.getValues().get("status"));

    handler.handleEnable(false);

    rsp = makeRequest(handler, req("action", "status"));
    assertEquals("disabled", rsp.getValues().get("status"));

  }

  public void testBadActionRaisesException() {
    SolrException se =
        expectThrows(SolrException.class, () -> makeRequest(handler, req("action", "badaction")));
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, se.code());
  }


  /**
   * Helper Method: Executes the request against the handler, returns the response, and closes the
   * request.
   */
  private SolrQueryResponse makeRequest(RequestHandlerBase handler, SolrQueryRequest req)
      throws Exception {

    SolrQueryResponse rsp = new SolrQueryResponse();
    try {
      handler.handleRequestBody(req, rsp);
    } finally {
      req.close();
    }
    return rsp;
  }

  /** Makes a query request and returns the JSON string response */
  /** This overrides the parent one in SolrTestCaseJ4 and uses wt=jsontee */
  public static String JQTee(SolrQueryRequest req) throws Exception {
    SolrParams params = req.getParams();
    if (!"jsontee".equals(params.get("wt", "xml")) || params.get("indent") == null) {
      ModifiableSolrParams newParams = new ModifiableSolrParams(params);
      newParams.set("wt", "jsontee");
      if (params.get("indent") == null) newParams.set("indent", "true");
      req.setParams(newParams);
    }

    String response;
    boolean failed = true;
    //try {
      response = h.query(req);
      //failed = false;
    //} finally {
      //if (failed) {
        //log.error("REQUEST FAILED: {}", req.getParamString());
      //}
    //}

    return response;
  }

  static class SolrPingWithDistrib extends SolrPing {
    public SolrPing setDistrib(boolean distrib) {
      getParams().add("distrib", distrib ? "true" : "false");
      return this;
    }
  }
}
