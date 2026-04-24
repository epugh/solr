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

load bats_helper

# A configSet name used throughout these tests
DESIGNER_CONFIGSET="bats_books"

setup_file() {
  common_clean_setup
  solr start
  solr assert --started http://localhost:${SOLR_PORT} --timeout 60000
}

teardown_file() {
  common_setup
  solr stop --all
}

setup() {
  common_setup
}

teardown() {
  save_home_on_failure

  # Best-effort cleanup of the designer draft so tests remain independent
  curl -s -X DELETE "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}" > /dev/null || true
}

# ---------------------------------------------------------------------------
# 1. List configs — the endpoint should return a JSON object with a configSets
#    property even when no designer drafts exist yet.
# ---------------------------------------------------------------------------
@test "list schema-designer configs returns JSON with configSets key" {
  run curl -s "http://localhost:${SOLR_PORT}/api/schema-designer/configs"
  assert_output --partial '"configSets"'
  refute_output --partial '"status":400'
  refute_output --partial '"status":500'
}

# ---------------------------------------------------------------------------
# 2. Prepare a new mutable draft configSet
# ---------------------------------------------------------------------------
@test "prep new schema-designer configSet" {
  run curl -s -X POST \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/prep?copyFrom=_default"
  assert_output --partial '"configSet"'
  refute_output --partial '"status":400'
  refute_output --partial '"status":500'
}

# ---------------------------------------------------------------------------
# 3. Get info for the prepared configSet
# ---------------------------------------------------------------------------
@test "get info for schema-designer configSet" {
  # Prepare first
  curl -s -X POST \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/prep?copyFrom=_default" \
    > /dev/null

  run curl -s "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/info"
  assert_output --partial '"configSet"'
  assert_output --partial "${DESIGNER_CONFIGSET}"
  refute_output --partial '"status":400'
  refute_output --partial '"status":500'
}

# ---------------------------------------------------------------------------
# 4. Analyze sample documents — sends books.json as the request body
# ---------------------------------------------------------------------------
@test "analyze sample documents for schema-designer configSet" {
  # Prepare the draft first
  curl -s -X POST \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/prep?copyFrom=_default" \
    > /dev/null

  run curl -s -X POST \
    -H "Content-Type: application/json" \
    --data-binary "@${SOLR_TIP}/example/exampledocs/books.json" \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/analyze"
  assert_output --partial '"configSet"'
  refute_output --partial '"status":400'
  refute_output --partial '"status":500'
}

# ---------------------------------------------------------------------------
# 5. Query the temporary collection — should return documents after analyze
# ---------------------------------------------------------------------------
@test "query schema-designer configSet returns documents" {
  # Prepare and analyze to load sample docs
  curl -s -X POST \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/prep?copyFrom=_default" \
    > /dev/null
  curl -s -X POST \
    -H "Content-Type: application/json" \
    --data-binary "@${SOLR_TIP}/example/exampledocs/books.json" \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/analyze" \
    > /dev/null

  run curl -s \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/query?q=*:*"
  assert_output --partial '"numFound"'
  refute_output --partial '"status":400'
  refute_output --partial '"status":500'
}

# ---------------------------------------------------------------------------
# 6. Download configSet zip via the schema-designer endpoint.
#    We verify:
#      - HTTP 200 response
#      - The response body is a valid zip (starts with the PK magic bytes)
# ---------------------------------------------------------------------------
@test "download schema-designer configSet as zip" {
  # Prepare the draft
  curl -s -X POST \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/prep?copyFrom=_default" \
    > /dev/null

  local zip_file="${BATS_TEST_TMPDIR}/${DESIGNER_CONFIGSET}.zip"
  local mutable_id="._designer_${DESIGNER_CONFIGSET}"

  # Capture HTTP status code separately
  local http_code
  http_code=$(curl -s -o "${zip_file}" -w "%{http_code}" \
    "http://localhost:${SOLR_PORT}/api/configsets/$(python3 -c "import urllib.parse; print(urllib.parse.quote('${mutable_id}', safe=''))")/files?displayName=${DESIGNER_CONFIGSET}")

  # Assert HTTP 200
  [ "${http_code}" = "200" ]

  # Assert the file was written and is non-empty
  [ -s "${zip_file}" ]

  # Assert the file starts with the ZIP magic bytes (PK = 0x504B)
  run bash -c "xxd '${zip_file}' | head -1"
  assert_output --partial '504b'
}

# ---------------------------------------------------------------------------
# 7. Download configSet zip — Content-Disposition header carries the filename
# ---------------------------------------------------------------------------
@test "download schema-designer configSet has correct Content-Disposition header" {
  # Prepare the draft
  curl -s -X POST \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/prep?copyFrom=_default" \
    > /dev/null

  local mutable_id="._designer_${DESIGNER_CONFIGSET}"
  run curl -s -I \
    "http://localhost:${SOLR_PORT}/api/configsets/$(python3 -c "import urllib.parse; print(urllib.parse.quote('${mutable_id}', safe=''))")/files?displayName=${DESIGNER_CONFIGSET}"
  assert_output --partial 'Content-Disposition'
  assert_output --partial '.zip'
}

# ---------------------------------------------------------------------------
# 8. Cleanup (DELETE) removes the designer draft
# ---------------------------------------------------------------------------
@test "cleanup schema-designer configSet succeeds" {
  # Prepare first so there is something to delete
  curl -s -X POST \
    "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}/prep?copyFrom=_default" \
    > /dev/null

  run curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "http://localhost:${SOLR_PORT}/api/schema-designer/${DESIGNER_CONFIGSET}"
  assert_output "200"
}
