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
package org.apache.solr.security.jwt;

import static org.apache.solr.security.jwt.JWTAuthPluginTest.JWT_TEST_PATH;

import org.apache.http.client.HttpClient;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.apache.HttpClientUtil;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.cloud.SolrCloudAuthTestCase;
import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Integration test that calls the JWT authentication plugin configuration API using SolrClient,
 * verifying end-to-end that the V2 API endpoint works and that unauthenticated requests are
 * rejected.
 */
@SolrTestCaseJ4.SuppressSSL
public class JWTAuthPluginConfigApiTest extends SolrCloudAuthTestCase {

  // RSA key pair matching the public key embedded in jwt_plugin_jwk_security.json
  private static final String JWK_JSON =
      "{\n"
          + "  \"kty\": \"RSA\",\n"
          + "  \"d\": \"i6pyv2z3o-MlYytWsOr3IE1olu2RXZBzjPRBNgWAP1TlLNaphHEvH5aHhe_CtBAastgFFMuP29CFhaL3_tGczkvWJkSveZQN2AHWHgRShKgoSVMspkhOt3Ghha4CvpnZ9BnQzVHnaBnHDTTTfVgXz7P1ZNBhQY4URG61DKIF-JSSClyh1xKuMoJX0lILXDYGGcjVTZL_hci4IXPPTpOJHV51-pxuO7WU5M9252UYoiYyCJ56ai8N49aKIMsqhdGuO4aWUwsGIW4oQpjtce5eEojCprYl-9rDhTwLAFoBtjy6LvkqlR2Ae5dKZYpStljBjK8PJrBvWZjXAEMDdQ8PuQ\",\n"
          + "  \"e\": \"AQAB\",\n"
          + "  \"use\": \"sig\",\n"
          + "  \"kid\": \"test\",\n"
          + "  \"alg\": \"RS256\",\n"
          + "  \"n\": \"jeyrvOaZrmKWjyNXt0myAc_pJ1hNt3aRupExJEx1ewPaL9J9HFgSCjMrYxCB1ETO1NDyZ3nSgjZis-jHHDqBxBjRdq_t1E2rkGFaYbxAyKt220Pwgme_SFTB9MXVrFQGkKyjmQeVmOmV6zM3KK8uMdKQJ4aoKmwBcF5Zg7EZdDcKOFgpgva1Jq-FlEsaJ2xrYDYo3KnGcOHIt9_0NQeLsqZbeWYLxYni7uROFncXYV5FhSJCeR4A_rrbwlaCydGxE0ToC_9HNYibUHlkJjqyUhAgORCbNS8JLCJH8NUi5sDdIawK9GTSyvsJXZ-QHqo4cMUuxWV5AJtaRGghuMUfqQ\"\n"
          + "}";

  private static String jwtTestToken;

  @BeforeClass
  public static void initJwtToken() throws Exception {
    PublicJsonWebKey jwk = RsaJsonWebKey.Factory.newPublicJwk(JWK_JSON);
    JwtClaims claims = JWTAuthPluginTest.generateClaims();
    JsonWebSignature jws = new JsonWebSignature();
    jws.setPayload(claims.toJson());
    jws.setKey(jwk.getPrivateKey());
    jws.setKeyIdHeaderValue(jwk.getKeyId());
    jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
    jwtTestToken = jws.getCompactSerialization();
  }

  @Override
  @After
  public void tearDown() throws Exception {
    shutdownCluster();
    super.tearDown();
  }

  /**
   * Verifies that the JWT authentication plugin configuration can be updated via the V2 API using
   * SolrClient, and that unauthenticated requests are rejected with a 401.
   */
  @Test
  public void testUpdateJwtConfigViaSolrClientV2Api() throws Exception {
    cluster =
        configureCluster(2)
            .withSecurityJson(
                JWT_TEST_PATH().resolve("security").resolve("jwt_plugin_jwk_security.json"))
            .addConfig(
                "conf1",
                JWT_TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
            .withDefaultClusterProperty("useLegacyReplicaAssignment", "false")
            .build();
    cluster.waitForAllNodes(10);

    String baseUrl = cluster.getRandomJetty(random()).getBaseUrl().toString();
    String v2AuthcUrl = baseUrl + "/____v2/cluster/security/authentication";
    HttpClient httpClient = HttpClientUtil.createClient(null);
    try {
      // Confirm initial state: blockUnknown=true (as configured in the security JSON)
      verifySecurityStatus(
          httpClient, v2AuthcUrl, "authentication/blockUnknown", "true", 20, "Bearer " + jwtTestToken);

      // An unauthenticated POST is rejected (no JWT token → 401)
      V2Request unauthRequest =
          new V2Request.Builder("/cluster/security/authentication")
              .withMethod(SolrRequest.METHOD.POST)
              .withPayload("{\"set-property\": {\"blockUnknown\": false}}")
              .build();
      try (SolrClient unauthClient = getHttpSolrClient(baseUrl)) {
        expectThrows(Exception.class, () -> unauthClient.request(unauthRequest));
      }

      // An authenticated POST with a valid JWT token succeeds and the config is updated
      V2Request updateRequest =
          new V2Request.Builder("/cluster/security/authentication")
              .withMethod(SolrRequest.METHOD.POST)
              .withPayload("{\"set-property\": {\"blockUnknown\": false}}")
              .build();
      updateRequest.addHeader("Authorization", "Bearer " + jwtTestToken);
      try (SolrClient solrClient = getHttpSolrClient(baseUrl)) {
        solrClient.request(updateRequest);
      }

      // Verify blockUnknown was changed to false
      verifySecurityStatus(
          httpClient, v2AuthcUrl, "authentication/blockUnknown", "false", 20, "Bearer " + jwtTestToken);
    } finally {
      HttpClientUtil.close(httpClient);
    }
  }
}
