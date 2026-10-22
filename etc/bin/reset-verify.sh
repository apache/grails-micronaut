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
tag=${1:?Usage: reset-verify.sh <tag> [download location]}
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]]
location=$(realpath "${2:-downloads}")
source_dir="$location/grails-micronaut"
if [[ -d "$source_dir" ]]; then
  test ! -e "$source_dir/.git"
  test -s "$source_dir/CHECKSUMS"
  test -s "$source_dir/PUBLISHED_ARTIFACTS"
  test "$(sed -n 's/^projectVersion=//p' "$source_dir/gradle.properties")" = "${tag#v}"
  read -r -p "Remove extracted source at $source_dir (including verification results)? [y/N] " confirm
  [[ "$confirm" =~ ^[Yy](es)?$ ]]
  rm -rf -- "$source_dir"
fi
file="apache-grails-micronaut-${tag#v}-src.zip"
for suffix in '' .asc .sha512; do
  rm -f -- "$location/$file$suffix" "$location/github-$file$suffix"
done
echo 'Verification scratch files removed.'
