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

package org.apache.solr.security.jwt.api;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.security.PermissionNameProvider;
import org.junit.Test;

public class V2JWTSecurityApiMappingTest extends SolrTestCaseJ4 {

  @Test
  public void testJwtConfigApiMapping() {
    // Verify the interface has the correct @Path annotation
    final Path pathAnnotation = ModifyJWTAuthPluginConfigAPI.class.getAnnotation(Path.class);
    assertNotNull("Expected @Path annotation on ModifyJWTAuthPluginConfigAPI", pathAnnotation);
    assertEquals("/cluster/security/authentication", pathAnnotation.value());

    // Verify the interface has a @POST method with @PermissionName(SECURITY_EDIT_PERM)
    boolean hasPostMethod = false;
    boolean hasSecurityEditPerm = false;
    for (Method m : ModifyJWTAuthPluginConfigAPI.class.getDeclaredMethods()) {
      if (m.isAnnotationPresent(POST.class)) {
        hasPostMethod = true;
        final PermissionName permAnnotation = m.getAnnotation(PermissionName.class);
        if (permAnnotation != null
            && permAnnotation.value() == PermissionNameProvider.Name.SECURITY_EDIT_PERM) {
          hasSecurityEditPerm = true;
        }
      }
    }
    assertTrue("Expected a @POST method on ModifyJWTAuthPluginConfigAPI", hasPostMethod);
    assertTrue(
        "Expected @PermissionName(SECURITY_EDIT_PERM) on the @POST method of ModifyJWTAuthPluginConfigAPI",
        hasSecurityEditPerm);
  }
}
