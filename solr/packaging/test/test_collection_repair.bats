#!/usr/bin/env bats

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This BATS style test is to test that the collection repair is working

load bats_helper

setup_file() {
  common_clean_setup
}

teardown_file() {
  common_setup
  solr stop --all
}

setup() {
  common_setup
}

teardown() {
  # save a snapshot of SOLR_HOME for failed tests
  save_home_on_failure
}


@test "Check lifecycle of collection repair" {


  #export clusters_dir="${BATS_TEST_TMPDIR}/clusters"
  export clusters_dir="/Users/epugh/Documents/projects/solr-epugh/clusters"

  
  mkdir -p ${clusters_dir}/solr1
  mkdir -p ${clusters_dir}/solr2
  mkdir -p ${clusters_dir}/solr3
  
  # set up three Solr nodes
  #solr start -e cloud --prompt-inputs 3,${SOLR_PORT},${SOLR2_PORT},${SOLR3_PORT},gettingstarted2,1,1,_default
  # # Get our three seperate independent Solr nodes running.
  solr start -p ${SOLR_PORT} -Dsolr.disable.allowUrls=true --solr-home ${clusters_dir}/solr1/solr
  
  bin/solr start -p 9100 -Dsolr.disable.allowUrls=true --solr-home /Users/epugh/Documents/projects/solr-epugh/solr1 --server-dir /Users/epugh/Documents/projects/solr-epugh/solr/packaging/build/dev/server
  solr start -p ${SOLR2_PORT} -z localhost:${ZK_PORT} -Dsolr.disable.allowUrls=true --solr-home ${clusters_dir}/solr2/solr
  solr start -p ${SOLR3_PORT} -z localhost:${ZK_PORT} -Dsolr.disable.allowUrls=true --solr-home --solr-home ${clusters_dir}/solr3/solr
    
  solr assert --started http://localhost:${SOLR_PORT}/solr --timeout 5000
  solr assert --started http://localhost:${SOLR2_PORT}/solr --timeout 5000
  solr assert --started http://localhost:${SOLR3_PORT}/solr --timeout 5000
  
  run curl -X POST -H 'Content-type: application/json' -d '{
      "add":{
          "name": ".cluster-event-producer",
          "class": "org.apache.solr.cluster.events.impl.DefaultClusterEventProducer"
      }}' http://localhost:${SOLR_PORT}/api/cluster/plugin

  assert_output --partial '"status":0'
  
  run curl -X POST -H 'Content-type: application/json' -d '{
      "add":{
          "name": "collections-repair-listener",
          "class": "org.apache.solr.cluster.events.impl.CollectionsRepairEventListener"
      }}' http://localhost:${SOLR_PORT}/api/cluster/plugin
  
  assert_output --partial '"status":0'
  
  # Wish bin/solr create supported nodeSet
  # Create a collection on Solr 1 and 2.
  run curl -X POST http://localhost:${SOLR_PORT}/api/collections -H 'Content-Type: application/json' -d "{
    \"name\": \"mycollection\",
    \"config\": \"_default\",
    \"numShards\": 1,
    \"replicationFactor\": 2,
    \"nodeSet\": [\"localhost:${SOLR_PORT}_solr\", \"localhost:${SOLR2_PORT}_solr\"]
  }"
  
  assert_output --partial '"status":0'
  sleep 5
  
  # shut Solr 2 down, which SHOULD cause the collections-repair-listener to 
  # add a copy on Solr 3.
  
  solr stop -p ${SOLR2_PORT}

  run solr healthcheck -c mycollection --verbose
  assert_output --partial '"status":"degraded"'
  assert_output --partial '"numShards":1'
  
  # Wait 30 for the repair to kick in.
  sleep 45
  
  run solr healthcheck -c mycollection --verbose
  assert_output --partial '"numShards":55'
  
  #solr assert --started http://localhost:${SOLR_PORT}/solr --timeout 5000


}
