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

package org.apache.solr.update.processor;

import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Utils;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.junit.After;
import org.junit.BeforeClass;

public class TemplateUpdateProcessorTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(5).addConfig("conf1", configset("cloud-minimal")).configure();
  }

  @After
  public void after() throws Exception {
    cluster.deleteAllCollections();
    cluster.shutdown();
  }

  public void testSimple() throws Exception {

    var cmd =
        new AddUpdateCommand(
            new LocalSolrQueryRequest(
                null,
                new ModifiableSolrParams()
                    .add("processor", "template")
                    .add("template.field", "id:{firstName}_{lastName}")
                    .add("template.field", "another:{lastName}_{firstName}")
                    .add("template.field", "missing:{lastName}_{unKnown}")));

    cmd.solrDoc = new SolrInputDocument();
    cmd.solrDoc.addField("firstName", "Tom");
    cmd.solrDoc.addField("lastName", "Cruise");

    new TemplateUpdateProcessorFactory()
        .getInstance(cmd.getReq(), new SolrQueryResponse(), null)
        .processAdd(cmd);
    assertEquals("Tom_Cruise", cmd.solrDoc.getFieldValue("id"));
    assertEquals("Cruise_Tom", cmd.solrDoc.getFieldValue("another"));
    assertEquals("Cruise_", cmd.solrDoc.getFieldValue("missing"));

    SolrInputDocument solrDoc = new SolrInputDocument();
    solrDoc.addField("id", "1");

    UpdateRequest add = new UpdateRequest().add(solrDoc);
    add.getParams()
        .add("processor", "template")
        .add("template.field", "x_s:key_{id}")
        .add("commit", "true");
    NamedList<Object> result =
        cluster
            .getSolrClient()
            .request(CollectionAdminRequest.createCollection("c", "conf1", 1, 1));
    Utils.toJSONString(result.asMap(4));
    AbstractFullDistribZkTestBase.waitForCollection(cluster.getZkStateReader(), "c", 1);
    cluster.getSolrClient().request(add, "c");
    QueryResponse rsp =
        cluster.getSolrClient().query("c", new ModifiableSolrParams().add("q", "id:1"));
    assertEquals("key_1", rsp.getResults().get(0).getFieldValue("x_s"));
  }
}
