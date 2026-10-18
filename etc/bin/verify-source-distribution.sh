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
tag=${1:?Usage: verify-source-distribution.sh <tag> [download location]}
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]]
version=${tag#v}
location=$(realpath "${2:-downloads}")
cd "$location"
file="apache-grails-micronaut-$version-src.zip"
sha512sum -c "$file.sha512"
gpg_home=$(mktemp -d)
trap 'rm -rf "$gpg_home"' EXIT
gpg --homedir "$gpg_home" --batch --import "$location/KEYS"
gpg --homedir "$gpg_home" --batch --verify "$file.asc" "$file"
entries=$(unzip -Z1 "$file")
while IFS= read -r entry; do
  [[ "$entry" == grails-micronaut/* && "$entry" != *'/../'* && "$entry" != *'\'* ]]
  if [[ "$entry" =~ /\.(git|github|omo|worktrees)(/|$) || "$entry" == *'/.claude/worktrees/'* ||
        "$entry" =~ ^grails-micronaut/(build-logic/)?(gradlew(\.bat)?|gradle/wrapper/gradle-wrapper\.(jar|properties))$ ||
        "$entry" == grails-micronaut/KEYS ]]; then
    echo "Forbidden source entry: $entry" >&2
    exit 1
  fi
done <<< "$entries"
if [[ -e grails-micronaut ]]; then
  echo 'Extracted source already exists. Use reset-verify.sh or a fresh download location.' >&2
  exit 1
fi
unzip -q "$file"
for required in LICENSE NOTICE README.md RELEASE.md PUBLISHED_ARTIFACTS CHECKSUMS BUILD_DATE .sdkmanrc; do
  test -s "grails-micronaut/$required"
done
test "$(sed -n 's/^projectVersion=//p' grails-micronaut/gradle.properties)" = "$version"
grep -Eq '^[0-9]+$' grails-micronaut/BUILD_DATE
for meta in LICENSE NOTICE; do
  while read -r referenced; do
    if [[ ! -f "grails-micronaut/$referenced" ]]; then
      echo "FAIL: $meta references missing license file $referenced" >&2
      exit 1
    fi
  done < <(sed -n 's/.*See \([^ ]*\) for the full license terms.*/\1/p' "grails-micronaut/$meta")
done
echo 'PASS: Source signature, checksum and contents verified'
