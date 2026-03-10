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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.DefaultSolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.NodeRoles;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.zookeeper.KeeperException;

/**
 * Utility methods for cluster V2 API implementations.
 *
 * <p>This class previously hosted the old-style {@code @EndPoint} V2 API implementations for {@code
 * /api/cluster}. These have been migrated to JAX-RS resources: {@link
 * org.apache.solr.handler.admin.api.ClusterNodeRoles} and {@link
 * org.apache.solr.handler.admin.api.Cluster}.
 */
public class ClusterAPI {

  private ClusterAPI() {
    /* Utility class - no instances */
  }

  public static List<String> getNodesByRole(
      NodeRoles.Role role, String mode, DistribStateManager zk)
      throws InterruptedException, IOException, KeeperException {
    try {
      return zk.listData(ZkStateReader.NODE_ROLES + "/" + role + "/" + mode);
    } catch (NoSuchElementException e) {
      return Collections.emptyList();
    }
  }

  public static SolrQueryRequest wrapParams(SolrQueryRequest req, Object... def) {
    Map<String, Object> m = Utils.makeMap(def);
    return wrapParams(req, m);
  }

  public static SolrQueryRequest wrapParams(SolrQueryRequest req, Map<String, Object> m) {
    ModifiableSolrParams solrParams = new ModifiableSolrParams();
    m.forEach(
        (k, v) -> {
          if (v == null) return;
          if (v instanceof String[]) {
            solrParams.add(k, (String[]) v);
          } else {
            solrParams.add(k, String.valueOf(v));
          }
        });
    DefaultSolrParams dsp = new DefaultSolrParams(req.getParams(), solrParams);
    req.setParams(dsp);
    return req;
  }
}
