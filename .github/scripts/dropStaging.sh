#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail
if [[ $# != 2 || ( "$1" != --id && "$1" != --description ) ]]; then
  echo "Usage: $0 <--id|--description> <value>" >&2
  exit 1
fi
: "${NEXUS_STAGE_DEPLOYER_USER:?}" "${NEXUS_STAGE_DEPLOYER_PW:?}" "${STAGING_PROFILE_ID:?}"
repos=$(curl -fsS -u "$NEXUS_STAGE_DEPLOYER_USER:$NEXUS_STAGE_DEPLOYER_PW" -H 'Accept: application/json' \
  "https://repository.apache.org/service/local/staging/profile_repositories/$STAGING_PROFILE_ID")
matches=$(jq --arg mode "$1" --arg value "$2" \
  '[.data[] | select(if $mode == "--id" then .repositoryId == $value else .description == $value end)]' <<< "$repos")
count=$(jq length <<< "$matches")
if [[ "$count" == 0 && "$1" == --description ]]; then
  echo "No staging repository found for $2. Nothing to drop."
  exit 0
fi
test "$count" = 1
state=$(jq -r '.[0].type' <<< "$matches")
[[ "$state" == open || "$state" == closed ]]
id=$(jq -r '.[0].repositoryId' <<< "$matches")
payload=$(jq -n --arg id "$id" '{data:{stagedRepositoryIds:[$id],description:("Drop " + $id)}}')
curl -fsS -u "$NEXUS_STAGE_DEPLOYER_USER:$NEXUS_STAGE_DEPLOYER_PW" \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  --data "$payload" https://repository.apache.org/service/local/staging/bulk/drop
echo "Successfully requested drop of repository $id."
