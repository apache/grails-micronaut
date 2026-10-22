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
if [[ $# != 3 ]]; then
  echo "Usage: $0 <tag> <svn-folder: micronaut> <ASF username>" >&2
  exit 1
fi
tag=$1
folder=$2
user=$3
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]]
test "$folder" = micronaut
test -n "$user"
read -r -s -p 'ASF password: ' password
echo
test -n "$password"
version=${tag#v}
dev="https://dist.apache.org/repos/dist/dev/grails/$folder/$version"
root="https://dist.apache.org/repos/dist/release/grails/$folder"
flags=(--non-interactive --username "$user" --password "$password")
svn ls "${flags[@]}" "$dev/sources"
if svn ls "${flags[@]}" "$root/$version" >/dev/null 2>&1; then
  echo 'Release destination already exists.' >&2
  exit 1
fi
read -r -p "Has the 72-hour PMC vote passed? Promote $dev? [y/N] " confirm
[[ "$confirm" =~ ^[Yy](es)?$ ]]
if ! svn ls "${flags[@]}" "$root" >/dev/null 2>&1; then
  svn mkdir "${flags[@]}" -m 'Create Grails Micronaut release folder' "$root"
fi
svn mv "${flags[@]}" -m "Promote Apache Grails Micronaut $version from dev to release" "$dev" "$root/$version"
echo 'Promoted. Remove superseded releases separately after checking the retention policy.'
