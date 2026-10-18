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
if [[ $# != 2 ]]; then
  echo "Usage: $0 <staging-repo-description> <ASF username>" >&2
  exit 1
fi
description=$1
user=$2
[[ "$description" =~ ^grails-micronaut:[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]]
test -n "$user"
read -r -s -p 'ASF password: ' password
echo
test -n "$password"
nexusApi() {
  local method=$1 path=$2
  shift 2
  curl -fsS -u "$user:$password" -H 'Accept: application/json' -H 'Content-Type: application/json' \
    -X "$method" "https://repository.apache.org/service/local$path" "$@"
}
repos=$(nexusApi GET /staging/profile_repositories)
matches=$(jq --arg d "$description" '[.data[] | select(.description == $d)]' <<< "$repos")
test "$(jq length <<< "$matches")" = 1
id=$(jq -r '.[0].repositoryId' <<< "$matches")
profile=$(jq -r '.[0].profileId' <<< "$matches")
test "$(jq -r '.[0].type' <<< "$matches")" = closed
read -r -p "Has the 72-hour PMC vote passed? Release $id? [y/N] " confirm
[[ "$confirm" =~ ^[Yy](es)?$ ]]
payload=$(jq -n --arg id "$id" --arg d "$description" '{data:{stagedRepositoryId:$id,description:$d}}')
nexusApi POST "/staging/profiles/$profile/promote" --data "$payload"
started=$(date +%s)
while :; do
  repos=$(nexusApi GET /staging/profile_repositories)
  transitioning=$(jq -r --arg id "$id" '.data[] | select(.repositoryId == $id) | .transitioning' <<< "$repos")
  state=$(jq -r --arg id "$id" '.data[] | select(.repositoryId == $id) | .type' <<< "$repos")
  if [[ "$transitioning" == false && "$state" == released ]]; then
    break
  fi
  if [[ "$transitioning" == false && "$state" != closed ]]; then
    echo "Unexpected staging state '$state' for $id. Not dropping it." >&2
    nexusApi GET "/staging/repository/$id/activity" >&2 || true
    exit 1
  fi
  if (( $(date +%s) - started >= 600 )); then
    echo 'Timed out waiting for promotion. Inspect Nexus activity before retrying.' >&2
    exit 1
  fi
  sleep 3
done
nexusApi POST "/staging/profiles/$profile/drop" --data "$payload"
echo "Released $id and requested staging repository cleanup."
