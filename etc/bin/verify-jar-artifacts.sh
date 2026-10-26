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
tag=${1:?Usage: verify-jar-artifacts.sh <tag> [download location]}
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]]
version=${tag#v}
location=$(realpath "${2:-downloads}")
source_dir="$location/grails-micronaut"
artifacts="$source_dir/PUBLISHED_ARTIFACTS"
checksums="$source_dir/CHECKSUMS"
test -s "$artifacts"
test -s "$checksums"
gpg_home=$(mktemp -d)
trap 'rm -rf "$gpg_home"' EXIT
gpg --homedir "$gpg_home" --batch --import "$location/KEYS"
work="$source_dir/etc/bin/results/first"
mkdir -p "$work"
cd "$work"
repo=${GRAILS_REPO_URL:-https://repository.apache.org/content/groups/staging}
declare -A seen=()
while read -r file coordinates extra; do
  test -z "$extra"
  IFS=: read -r group artifact artifact_version classifier <<< "$coordinates"
  test "$group" = org.apache.grails
  test "$artifact_version" = "$version"
  case "$artifact" in
    grails-micronaut|grails-micronaut-bom|grails-hibernate5-micronaut-bom|grails-hibernate7-micronaut-bom) ;;
    *) echo "Unexpected coordinate: $coordinates" >&2; exit 1 ;;
  esac
  case "$file" in
    "$artifact-$version.jar"|"$artifact-$version-sources.jar"|"$artifact-$version-javadoc.jar"|"$artifact-$version.pom"|"$artifact-$version.module") ;;
    *) echo "Unexpected artifact file: $file" >&2; exit 1 ;;
  esac
  if [[ "$file" == *.jar ]]; then
    test "$file" = "$artifact-$version${classifier:+-$classifier}.jar"
  else
    test -z "$classifier"
  fi
  test -z "${seen[$file]:-}"
  seen[$file]=1
  url="$repo/org/apache/grails/$artifact/$version/$file"
  curl -fsSL -o "$file" "$url"
  curl -fsSL -o "$file.asc" "$url.asc"
  gpg --homedir "$gpg_home" --batch --verify "$file.asc" "$file"
  expected=$(awk -v f="$file" '$1 == f {print $2}' "$checksums")
  [[ "$expected" =~ ^[0-9a-fA-F]{128}$ ]]
  printf '%s  %s\n' "$expected" "$file" | sha512sum -c -
  if [[ "$file" == *.pom || "$file" == *.module ]]; then
    if grep -q SNAPSHOT "$file"; then
      echo "SNAPSHOT dependency in $file" >&2
      exit 1
    fi
  elif [[ "$file" != *-javadoc.jar ]]; then
    jar_entries=$(jar tf "$file")
    for required in META-INF/LICENSE META-INF/NOTICE; do
      grep -Fx "$required" <<< "$jar_entries" >/dev/null
      while read -r referenced; do
        grep -Fx "META-INF/$referenced" <<< "$jar_entries" >/dev/null
      done < <(unzip -p "$file" "$required" | sed -n 's/.*See \([^ ]*\) for the full license terms.*/\1/p')
    done
    sbom=$(unzip -p "$file" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Sbom-Location: *//p')
    if [[ -n "$sbom" ]]; then
      grep -Fx "$sbom" <<< "$jar_entries" >/dev/null
    fi
  fi
done < "$artifacts"
for artifact in grails-micronaut grails-micronaut-bom grails-hibernate5-micronaut-bom grails-hibernate7-micronaut-bom; do
  test "${seen[$artifact-$version.pom]:-}" = 1
done
test "${seen[grails-micronaut-$version.jar]:-}" = 1
diff -u <(awk '{print $1}' "$artifacts" | sort) <(awk '{print $1}' "$checksums" | sort)
echo 'PASS: All four coordinates, signatures, checksums and JAR licenses verified'
