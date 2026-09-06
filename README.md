<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Apache Grails Micronaut

[![CI](https://github.com/apache/grails-micronaut/actions/workflows/gradle.yml/badge.svg?branch=8.0.x)](https://github.com/apache/grails-micronaut/actions/workflows/gradle.yml)

This repository contains the Grails Micronaut plugin, its Micronaut-specific BOMs, and focused integration examples.

The build consumes published `org.apache.grails` artifacts from the Grails 8.0.x repositories. Only the four artifacts owned by this repository are built locally:

- `org.apache.grails:grails-micronaut`
- `org.apache.grails:grails-micronaut-bom`
- `org.apache.grails:grails-hibernate5-micronaut-bom`
- `org.apache.grails:grails-hibernate7-micronaut-bom`

## Requirements

- JDK 25
- The included Gradle wrapper

## Build

```shell
./gradlew build
```

See [the Micronaut integration guide](docs/micronaut.adoc) for consumer configuration.

## Licensing

Apache Grails Micronaut is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
