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

import org.apache.solr.SolrTestCaseJ4.SuppressSSL;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.impl.JavaBinRequestWriter;
import org.apache.solr.client.solrj.impl.JavaBinResponseParser;
import org.junit.BeforeClass;

/**
 * A subclass of SolrExampleTests that explicitly uses the HTTP1 client and the binary codec for
 * communication.
 */
@SuppressSSL(bugUrl = "https://issues.apache.org/jira/browse/SOLR-5776")
public class SolrExampleBinaryTest extends SolrExampleTests {
  @BeforeClass
  public static void beforeTest() throws Exception {
    createAndStartJetty(legacyExampleCollection1SolrHome());
  }

  @Override
  public SolrClient createNewSolrClient() {
    return new HttpSolrClient.Builder(getBaseUrl())
        .withDefaultCollection(DEFAULT_TEST_CORENAME)
        .allowMultiPartPost(random().nextBoolean())
        .withRequestWriter(new JavaBinRequestWriter())
        .withResponseParser(new JavaBinResponseParser())
        .build();
  }
}
