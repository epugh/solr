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
package org.apache.solr.client.solrj.request;

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.CloudSolrClient;

/**
 * Marker class so that we can determine which requests are updates.
 *
 * @deprecated Use {@link UpdateRequest#isSendToLeaders} and {@link SolrRequest#getRequestType}.
 */
@Deprecated
public interface IsUpdateRequest {

  /**
   * Indicates if clients should make attempts to route this request to a shard leader, overriding
   * typical client routing preferences for requests. Defaults to true.
   *
   * @see CloudSolrClient#isUpdatesToLeaders
   */
  default boolean isSendToLeaders() {
    return true;
  }
}
