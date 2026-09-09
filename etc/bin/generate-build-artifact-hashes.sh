#!/usr/bin/env bash
#
#  Licensed to the Apache Software Foundation (ASF) under one or more
#  contributor license agreements.  See the NOTICE file distributed with
#  this work for additional information regarding copyright ownership.
#  The ASF licenses this file to You under the Apache License, Version 2.0
#  (the "License"); you may not use this file except in compliance with
#  the License.  You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

# GNU coreutils (sha512sum, realpath). Prefer the verification container.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../.." && pwd)

if [ "${1:-}" != '' ]; then
  if [ -d "$1" ]; then
    SCAN=$(cd "$1" && pwd)
  elif [ -d "${ROOT}/$1" ]; then
    SCAN=$(cd "${ROOT}/$1" && pwd)
  else
    echo "❌  Path '${1}' does not exist." >&2
    exit 1
  fi
  ROOT="${SCAN}"
else
  SCAN="${ROOT}"
fi

find "${SCAN}" -type f -name '*.jar' \
  ! -path '*/buildSrc/*' \
  ! -path '*/etc/*' \
  ! -path '*/grails-test-examples/*' \
  -path '*/build/libs/*.jar' \
  | sort \
  | while IFS= read -r jar; do
      rel=$(realpath --relative-to="${ROOT}" "${jar}")
      hash=$(sha512sum "${jar}" | awk '{print $1}')
      printf '%s %s\n' "${rel}" "${hash}"
    done
