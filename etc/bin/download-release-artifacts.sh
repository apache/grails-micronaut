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
tag=${1:?Usage: download-release-artifacts.sh <tag> [download location]}
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]]
version=${tag#v}
location=$(realpath -m "${2:-downloads}")
mkdir -p "$location"
file="apache-grails-micronaut-$version-src.zip"
for suffix in '' .asc .sha512; do
  curl -fsSL -o "$location/github-$file$suffix" "https://github.com/apache/grails-micronaut/releases/download/$tag/$file$suffix"
  curl -fsSL -o "$location/$file$suffix" "https://dist.apache.org/repos/dist/dev/grails/micronaut/$version/sources/$file$suffix"
  cmp "$location/$file$suffix" "$location/github-$file$suffix"
done
echo 'PASS: GitHub and SVN source release files match'
