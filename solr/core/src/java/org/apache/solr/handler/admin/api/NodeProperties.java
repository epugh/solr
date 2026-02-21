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
package org.apache.solr.handler.admin.api;

import static org.apache.solr.security.PermissionNameProvider.Name.CONFIG_READ_PERM;

import jakarta.inject.Inject;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.solr.api.JerseyResource;
import org.apache.solr.client.api.endpoint.NodePropertiesApi;
import org.apache.solr.client.api.model.NodePropertiesResponse;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.jersey.PermissionName;

/**
 * V2 API implementation for listing system properties on a node.
 *
 * <p>This API (GET /v2/node/properties) is analogous to the v1 /admin/info/properties.
 */
public class NodeProperties extends JerseyResource implements NodePropertiesApi {

  private final CoreContainer coreContainer;

  @Inject
  public NodeProperties(CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
  }

  @Override
  @PermissionName(CONFIG_READ_PERM)
  public NodePropertiesResponse getProperties(String name) throws Exception {
    final NodePropertiesResponse response = instantiateJerseyResponse(NodePropertiesResponse.class);
    final Map<String, String> props = new LinkedHashMap<>();
    if (name != null) {
      props.put(name, coreContainer.getNodeConfig().getRedactedSysPropValue(name));
    } else {
      final Enumeration<?> propertyNames = System.getProperties().propertyNames();
      while (propertyNames.hasMoreElements()) {
        final String propName = (String) propertyNames.nextElement();
        props.put(propName, coreContainer.getNodeConfig().getRedactedSysPropValue(propName));
      }
    }
    response.systemProperties = props;
    return response;
  }
}
