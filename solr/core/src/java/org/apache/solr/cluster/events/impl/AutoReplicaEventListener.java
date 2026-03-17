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
package org.apache.solr.cluster.events.impl;

import com.google.common.annotations.VisibleForTesting;
import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.ClusterSingleton;
import org.apache.solr.cluster.events.ClusterEvent;
import org.apache.solr.cluster.events.ClusterEventListener;
import org.apache.solr.cluster.events.NodesUpEvent;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.core.CoreContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener that automatically creates replicas on newly added nodes.
 *
 * <p>When nodes are added to the cluster, this listener will automatically create replicas for
 * configured collections on those new nodes, distributing load across the cluster.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>managedCollections: comma-separated list of collection names to manage (default: all)
 *   <li>replicasPerShard: number of replicas to create per shard on new nodes (default: 1)
 * </ul>
 */
public class AutoReplicaEventListener implements ClusterEventListener, ClusterSingleton, Closeable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String PLUGIN_NAME = "autoReplicaListener";
  private static final String ASYNC_ID_PREFIX = "_async_" + PLUGIN_NAME;
  private static final AtomicInteger counter = new AtomicInteger();

  private final SolrClient solrClient;
  private final SolrCloudManager solrCloudManager;
  private final CoreContainer cc;

  private State state = State.STOPPED;

  private Set<String> managedCollections = new HashSet<>();
  private int replicasPerShard = 1;

  public AutoReplicaEventListener(CoreContainer cc) {
    this.cc = cc;
    this.solrClient = cc.getZkController().getSolrClient();
    this.solrCloudManager = cc.getZkController().getSolrCloudManager();
  }

  /**
   * Set the collections to manage. If empty, all collections will be managed.
   *
   * @param collectionNames comma-separated list of collection names
   */
  @VisibleForTesting
  public void setManagedCollections(String collectionNames) {
    if (collectionNames == null || collectionNames.trim().isEmpty()) {
      managedCollections.clear();
    } else {
      managedCollections = new HashSet<>(Arrays.asList(collectionNames.trim().split("\\s*,\\s*")));
    }
    if (log.isInfoEnabled()) {
      log.info(
          "Managing collections: {}", managedCollections.isEmpty() ? "ALL" : managedCollections);
    }
  }

  /**
   * Set the number of replicas to create per shard on new nodes.
   *
   * @param replicasPerShard number of replicas per shard (default: 1)
   */
  @VisibleForTesting
  public void setReplicasPerShard(int replicasPerShard) {
    if (replicasPerShard < 1) {
      throw new IllegalArgumentException("replicasPerShard must be at least 1");
    }
    this.replicasPerShard = replicasPerShard;
    if (log.isInfoEnabled()) {
      log.info("Replicas per shard: {}", replicasPerShard);
    }
  }

  @Override
  public String getName() {
    return PLUGIN_NAME;
  }

  @Override
  public void onEvent(ClusterEvent event) {
    if (state != State.RUNNING) {
      // ignore the event
      return;
    }
    switch (event.getType()) {
      case NODES_UP:
        handleNodesUp((NodesUpEvent) event);
        break;
      default:
        if (log.isDebugEnabled()) {
          log.debug("Unsupported event {}, ignoring...", event);
        }
    }
  }

  private void handleNodesUp(NodesUpEvent event) {
    List<String> newNodes = new ArrayList<>();
    event.getNodeNames().forEachRemaining(newNodes::add);

    if (newNodes.isEmpty()) {
      return;
    }

    if (log.isInfoEnabled()) {
      log.info("Detected new nodes: {}, creating replicas...", newNodes);
    }

    try {
      // Get all collections
      Collection<String> collections = solrCloudManager.getClusterState().getCollectionNames();

      for (String collectionName : collections) {
        // Skip if not in managed collections (unless managing all)
        if (!managedCollections.isEmpty() && !managedCollections.contains(collectionName)) {
          if (log.isDebugEnabled()) {
            log.debug("Skipping collection {} (not in managed list)", collectionName);
          }
          continue;
        }

        DocCollection collection = solrCloudManager.getClusterState().getCollection(collectionName);

        // For each shard in the collection
        collection
            .getActiveSlices()
            .forEach(
                shard -> {
                  // For each new node, create replicas
                  for (String nodeName : newNodes) {
                    for (int i = 0; i < replicasPerShard; i++) {
                      try {
                        CollectionAdminRequest.AddReplica addReplica =
                            CollectionAdminRequest.addReplicaToShard(
                                collectionName, shard.getName());
                        addReplica.setNode(nodeName);
                        addReplica.setAsyncId(ASYNC_ID_PREFIX + counter.incrementAndGet());

                        if (log.isInfoEnabled()) {
                          log.info(
                              "Creating replica for collection={}, shard={} on node={}",
                              collectionName,
                              shard.getName(),
                              nodeName);
                        }

                        solrClient.request(addReplica);
                      } catch (Exception e) {
                        if (log.isWarnEnabled()) {
                          log.warn(
                              "Exception creating replica for collection={}, shard={} on node={}: {}",
                              collectionName,
                              shard.getName(),
                              nodeName,
                              e.toString());
                        }
                      }
                    }
                  }
                });
      }
    } catch (IOException e) {
      if (log.isErrorEnabled()) {
        log.error("Exception handling nodes up event", e);
      }
    }
  }

  @Override
  public void start() throws Exception {
    if (log.isInfoEnabled()) {
      log.info("Starting AutoReplicaEventListener");
    }
    state = State.STARTING;
    state = State.RUNNING;
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void stop() {
    if (log.isInfoEnabled()) {
      log.info("Stopping AutoReplicaEventListener");
    }
    state = State.STOPPING;
    state = State.STOPPED;
  }

  @Override
  public void close() throws IOException {
    if (log.isDebugEnabled()) {
      log.debug("Closing AutoReplicaEventListener");
    }
    stop();
  }
}
