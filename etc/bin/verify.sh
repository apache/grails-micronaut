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
if [[ $# -lt 1 || ! "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]]; then
  echo "Usage: $0 <release-tag> [download location]" >&2
  exit 1
fi
tag=$1
location=$(realpath -m "${2:-downloads}")
scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
trap 'echo "FAIL: Release verification failed." >&2' ERR
for tool in java jar gpg curl unzip sha512sum realpath awk diff sort cmp; do
  command -v "$tool" >/dev/null
done
if command -v gradlew >/dev/null; then
  GRADLE_CMD=$(command -v gradlew)
else
  GRADLE_CMD=$(command -v gradle)
fi
export GRADLE_CMD
mkdir -p "$location"
"$scripts/verify-keys.sh" "$location"
"$scripts/download-release-artifacts.sh" "$tag" "$location"
"$scripts/verify-source-distribution.sh" "$tag" "$location"
source_dir="$location/grails-micronaut"
expected_gradle=$(sed -n 's/^gradle=//p' "$source_dir/.sdkmanrc")
actual_gradle=$("$GRADLE_CMD" --version | awk '/^Gradle / {print $2}')
test "$actual_gradle" = "$expected_gradle"
expected_java=$(sed -n 's/^java=\([^-]*\)-librca$/\1/p' "$source_dir/.sdkmanrc")
java_settings=$(java -XshowSettings:properties -version 2>&1)
actual_java=$(awk '/java.version =/ {print $3}' <<< "$java_settings")
test -n "$expected_java"
test "$actual_java" = "$expected_java"
grep -qi BellSoft <<< "$java_settings"
"$scripts/verify-jar-artifacts.sh" "$tag" "$location"
SOURCE_DATE_EPOCH=$(cat "$source_dir/BUILD_DATE")
export SOURCE_DATE_EPOCH
"$GRADLE_CMD" -p "$source_dir" :rat --no-build-cache
echo 'PASS: RAT passed'
"$scripts/verify-reproducible.sh" "$location"
echo 'PASS: Automatic release verification finished. Review the source, licenses and integration tests before voting.'
