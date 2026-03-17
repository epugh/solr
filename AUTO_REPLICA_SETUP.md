# Auto Replica Event Listener Setup Guide

This guide explains how to enable automatic replica creation when nodes are added to your Solr cluster.

## Overview

The `AutoReplicaEventListener` automatically creates replicas on newly added nodes, helping to distribute load across your cluster. This feature requires two components:

1. **DefaultClusterEventProducer** - Generates cluster events (NODES_UP, NODES_DOWN, etc.)
2. **AutoReplicaEventListener** - Listens for NODES_UP events and creates replicas

## Step 1: Enable the Cluster Event Producer

By default, Solr uses a no-op event producer that doesn't generate any events. You need to configure the `DefaultClusterEventProducer`:

```bash
curl -X POST -H 'Content-type: application/json' -d '{
    "add":{
        "name": ".cluster-event-producer",
        "class": "org.apache.solr.cluster.events.impl.DefaultClusterEventProducer"
    }}' http://localhost:8983/api/cluster/plugin
```

## Step 2: Configure the Auto Replica Listener

### Option A: Manage All Collections

To automatically create replicas for all collections when nodes are added:

```bash
curl -X POST -H 'Content-type: application/json' -d '{
    "add":{
        "name": "auto-replica-listener",
        "class": "org.apache.solr.cluster.events.impl.AutoReplicaEventListener"
    }}' http://localhost:8983/api/cluster/plugin
```

### Option B: Manage Specific Collections

To only manage specific collections:

```bash
curl -X POST -H 'Content-type: application/json' -d '{
    "add":{
        "name": "auto-replica-listener",
        "class": "org.apache.solr.cluster.events.impl.AutoReplicaEventListener",
        "config":{
          "managedCollections": "collection1,collection2",
          "replicasPerShard": 1
    }}}' http://localhost:8983/api/cluster/plugin
```

## Configuration Parameters

- `managedCollections` (optional): Comma-separated list of collection names to manage. If not specified, all collections are managed.
- `replicasPerShard` (optional): Number of replicas to create per shard on new nodes. Default is 1.

## Verify Configuration

Check that the plugins are loaded:

```bash
curl http://localhost:8983/api/cluster/plugin
```

You should see both `.cluster-event-producer` and `auto-replica-listener` in the response.

## Testing

1. Create a test collection:
   ```bash
   curl "http://localhost:8983/solr/admin/collections?action=CREATE&name=test&numShards=1&replicationFactor=1"
   ```

2. Add a new Solr node to your cluster

3. Check the collection status - you should see a new replica created on the new node:
   ```bash
   curl "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&collection=test"
   ```

## Troubleshooting

### Events Not Being Generated

If you don't see events being generated when nodes are added/removed:

1. Verify the event producer is configured:
   ```bash
   curl http://localhost:8983/api/cluster/plugin | grep cluster-event-producer
   ```

2. Check the Solr logs for event-related messages (set log level to DEBUG for `org.apache.solr.cluster.events`)

### Replicas Not Being Created

If events are generated but replicas aren't created:

1. Verify the listener is configured and running
2. Check if the collection is in the `managedCollections` list (if specified)
3. Review Solr logs for any errors during replica creation

## Removing the Configuration

To disable automatic replica creation:

```bash
curl -X POST -H 'Content-type: application/json' -d '{
    "remove": "auto-replica-listener"
}' http://localhost:8983/api/cluster/plugin
```

To disable all event generation:

```bash
curl -X POST -H 'Content-type: application/json' -d '{
    "remove": ".cluster-event-producer"
}' http://localhost:8983/api/cluster/plugin
```

## See Also

- Full documentation: `solr/solr-ref-guide/modules/configuration-guide/pages/cluster-plugins.adoc`
- Implementation: `solr/core/src/java/org/apache/solr/cluster/events/impl/AutoReplicaEventListener.java`
- Tests: `solr/core/src/test/org/apache/solr/cluster/events/impl/AutoReplicaEventListenerTest.java`
