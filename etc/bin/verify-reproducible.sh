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
location=$(realpath "${1:-downloads}")
source_dir="$location/grails-micronaut"
scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
results="$source_dir/etc/bin/results"
test -s "$source_dir/CHECKSUMS"
test -d "$results/first"
SOURCE_DATE_EPOCH=$(cat "$source_dir/BUILD_DATE")
export SOURCE_DATE_EPOCH
export TEST_BUILD_REPRODUCIBLE=true
export GRAILS_PUBLISH_RELEASE=true
if [[ -z "${GRADLE_CMD:-}" ]]; then
  if command -v gradlew >/dev/null; then
    GRADLE_CMD=$(command -v gradlew)
  else
    GRADLE_CMD=$(command -v gradle)
  fi
fi
cd "$source_dir"
find . -depth \( -type f -o -type d \) -exec touch -d "@${SOURCE_DATE_EPOCH}" {} +
find . -depth -type l -exec touch -h -d "@${SOURCE_DATE_EPOCH}" {} +
"$GRADLE_CMD" -p "$source_dir" build :aggregateChecksums :aggregatePublishedArtifacts --rerun-tasks --no-build-cache --no-daemon
mkdir -p "$results/second"
"$scripts/generate-build-artifact-hashes.sh" "$source_dir" > "$results/rebuilt-jars.txt"
find . -path ./etc -prune -o -type f \( -path '*/build/libs/*.jar' -o -path '*/build/publication-metadata/*' \) -exec cp -t "$results/second" -- {} +
sort "$source_dir/CHECKSUMS" > "$results/first.txt"
sort build/CHECKSUMS.txt > "$results/second.txt"
if ! diff -u "$results/first.txt" "$results/second.txt" > "$results/diff.txt"; then
  cat "$results/diff.txt"
  echo "FAIL: Rebuilt artifacts differ. Inspect $results/first and $results/second with extract-build-artifact.sh." >&2
  exit 1
fi
diff -u <(sort "$source_dir/PUBLISHED_ARTIFACTS") <(sort build/PUBLISHED_ARTIFACTS.txt)
echo 'PASS: Rebuilt JARs, BOM POMs and module metadata match the staged artifacts'
