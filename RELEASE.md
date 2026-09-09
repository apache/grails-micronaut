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

# Apache Grails Micronaut Release Process

This document describes how to release Apache Grails Micronaut. This repository is a standalone
plugin and BOM set. It is **not** grails-core: there is no CLI, wrapper, Forge, SDKMAN, or
dual-JDK "Micronaut island". Every published artifact is built on **Liberica JDK 25.0.3**.

The four published coordinates are:

- `org.apache.grails:grails-micronaut`
- `org.apache.grails:grails-micronaut-bom`
- `org.apache.grails:grails-hibernate5-micronaut-bom`
- `org.apache.grails:grails-hibernate7-micronaut-bom`

GitHub Actions is [untrusted hardware](https://www.apache.org/legal/release-policy.html#owned-controlled-hardware)
under ASF policy, so a release must be **verifiable**: anyone can rebuild the tagged source
elsewhere and show the JARs are equivalent. That is why `SOURCE_DATE_EPOCH`, archive
determinism, and `etc/bin/test-reproducible-builds.sh` exist.

## Contents

- [Versioning](#versioning)
- [1. Prerequisites](#1-prerequisites)
- [2. Cut the release](#2-cut-the-release)
- [3. What the `publish` job does](#3-what-the-publish-job-does)
- [3.1 Stage the signed source distribution](#31-stage-the-signed-source-distribution)
- [4. Verify staged artifacts](#4-verify-staged-artifacts)
- [5. Vote](#5-vote)
- [6. Promote](#6-promote)
- [Rollback](#rollback)
- [Appendix: GPG and KEYS](#appendix-gpg-and-keys)
- [Appendix: Verification from a container](#appendix-verification-from-a-container)
- [Appendix: Why the build must be reproducible](#appendix-why-the-build-must-be-reproducible)

## Versioning

This line follows the Grails framework version scheme:

```
MAJOR.MINOR.PATCH[-MODIFIER]
```

- **MAJOR / MINOR / PATCH** have the same meaning as grails-core.
- **MODIFIER** is `-M<n>` (milestone), `-RC<n>` (release candidate), `-SNAPSHOT`, or omitted for a final release.

Tags are `v` plus the version (`v8.0.0-M1`). The workflow strips the prefix
(`RELEASE_VERSION=${GITHUB_REF_NAME#v}`) and passes `-PprojectVersion=${RELEASE_VERSION}`.
The checked-in default in `gradle.properties` stays a snapshot (`8.0.0-SNAPSHOT`) between releases.

Milestones and RCs go through the same process as a final release.

## 1. Prerequisites

- The tag points at the exact commit to release. No SNAPSHOT dependencies; Apache
  [release policy](https://www.apache.org/legal/release-policy.html) forbids them.
- JDK is pinned in [`.sdkmanrc`](.sdkmanrc) (`25.0.3-librca`). CI and
  [`etc/bin/Dockerfile`](etc/bin/Dockerfile) must use the same Liberica patch.
- Secrets and variables:

  | Name | Purpose |
  | --- | --- |
  | `GRAILS_GPG_KEY` | Private key imported for signing. |
  | `GPG_KEY_ID` / `SIGNING_KEY` | Key id passed to Gradle signing. |
  | `NEXUS_STAGE_DEPLOYER_USER` | Staging username. |
  | `NEXUS_STAGE_DEPLOYER_PW` | Staging password. |
  | `STAGING_PROFILE_ID` | Nexus staging profile. |
  | `STAGING_URL` | Repository variable for the staging API URL. |
  | `DEVELOCITY_ACCESS_KEY` | Gradle Develocity (optional for a local rebuild). |

  Multi-line secrets such as the GPG key need a trailing newline.

- The `release` / `close` / `docs` environments in [`.asf.yaml`](.asf.yaml) list reviewers
  (GitHub caps private-group approvers; at most 6 named users). The current
  [`release.yml`](.github/workflows/release.yml) stages JARs without those environment
  gates. Promotion of the closed Nexus staging repository remains a **manual** PMC step.

## 2. Cut the release

1. Open <https://github.com/apache/grails-micronaut/releases> and **Draft a new release**.
2. Tag `v<version>` (for example `v8.0.0-M1`) targeting `8.0.x` (or the maintenance branch).
3. Generate release notes, then edit them.
4. Check **Set as a pre-release** for milestones and RCs.
5. **Publish release**. That starts the `Release` workflow.

## 3. What the `publish` job does

The job in [`.github/workflows/release.yml`](.github/workflows/release.yml):

1. Checks out the tag with full history.
2. Sets `SOURCE_DATE_EPOCH` from the last commit time and `touch`es the tree to that epoch
   so archive and documentation timestamps match a local rebuild.
3. Uses Liberica JDK `25.0.3`.
4. Runs `./gradlew build rat -PprojectVersion=${RELEASE_VERSION} --no-build-cache`.
5. Initializes a Sonatype staging repository.
6. Imports `GRAILS_GPG_KEY` and publishes the four artifacts with
   `publishToSonatype closeSonatypeStagingRepository aggregateChecksums aggregatePublishedArtifacts`.
7. Writes `build/BUILD_DATE.txt`, `build/CHECKSUMS.txt`, and `build/PUBLISHED_ARTIFACTS.txt`
   and attaches them to the GitHub Release.

The staging repository is **closed**, not released. Do not promote it until verification
and the vote succeed.

## 3.1 Stage the signed source distribution

ASF [release policy](https://www.apache.org/legal/release-policy.html) requires every
release to include a **signed source package**. Maven JARs are convenience binaries.
The official vote artifact is the source zip, staged under
`https://dist.apache.org/repos/dist/dev/grails/micronaut/<version>/sources/`
before the vote starts. This repository does not yet automate that zip; do it
from the tagged commit:

1. Check out the release tag. Remove files that must not ship in an ASF source
   release (`.git`, `.github`, `.asf.yaml`, and `gradle-wrapper.jar` if your
   bootstrap regenerates it).
2. Copy `build/CHECKSUMS.txt`, `build/PUBLISHED_ARTIFACTS.txt`, and
   `build/BUILD_DATE.txt` from the GitHub Release into the tree as `CHECKSUMS`,
   `PUBLISHED_ARTIFACTS`, and `BUILD_DATE`.
3. Zip to `apache-grails-micronaut-<version>-src.zip`.
4. Detached-sign with the Grails key (`*.asc`) and write `*.sha512`.
5. Commit those three files to
   `dist/dev/grails/micronaut/<version>/sources/` using your ASF SVN credentials
   (infra grants the GitHub secret write to `dev` only).
6. Attach the same zip, `.asc`, and `.sha512` to the GitHub Release.

After the vote passes, a PMC member moves that folder from `dist/dev` to
`dist/release` under their own credentials. Automating the zip job can follow
[apache/grails-gradle-publish](https://github.com/apache/grails-gradle-publish)
`.github/workflows/release.yaml`.

## 4. Verify staged artifacts

### 4.1 License audit

```shell
./gradlew rat
```

### 4.2 Same-machine reproducibility

From a clean checkout of the tag, with GNU coreutils (Linux or the verification container):

```shell
etc/bin/test-reproducible-builds.sh
```

That pins `SOURCE_DATE_EPOCH` to the last commit, builds twice with
`--rerun-tasks --no-build-cache -PskipTests`, and compares SHA-512 hashes of JARs under
`build/libs` (test examples are excluded). On success the diff is empty. Failures leave
`etc/bin/results/first.txt`, `second.txt`, `diff.txt`, and copies of the differing JARs.

The script runs `git clean -xdf` (it keeps `etc/bin`, `.idea`, and `.gradle`). Use a
throwaway checkout.

### 4.3 Rebuild vs staged JARs

Use `build/CHECKSUMS.txt` and `build/PUBLISHED_ARTIFACTS.txt` from the GitHub Release:

1. Import [KEYS](KEYS) or <https://dist.apache.org/repos/dist/release/grails/KEYS>.
2. Download each JAR (and `.asc`) from the Nexus staging repository listed in
   `PUBLISHED_ARTIFACTS.txt`.
3. `gpg --verify` each signature.
4. Compare SHA-512 to `CHECKSUMS.txt`.
5. Rebuild locally with the same `SOURCE_DATE_EPOCH` as `BUILD_DATE.txt` and
   `TEST_BUILD_REPRODUCIBLE=true` (disables signing) and compare hashes.

Prefer the container in [Appendix: Verification from a container](#appendix-verification-from-a-container)
so the OS matches GitHub Actions.

### 4.4 JAR contents

Non-javadoc JARs must contain `META-INF/LICENSE` and `META-INF/NOTICE`. POMs must not
reference SNAPSHOT versions.

## 5. Vote

Follow the [Apache voting process](https://www.apache.org/foundation/voting.html) on
[dev@grails.apache.org](https://lists.apache.org/list.html?dev@grails.apache.org).
Only Grails PMC votes are binding. Minimum 72 hours and three PMC +1 votes.

Include the tag, commit SHA, staging repository URL, GitHub Release URL, and checksum
file URLs so voters can inspect the same bits.

## 6. Promote

After the vote passes:

1. Release the closed Nexus staging repository on
   <https://repository.apache.org/#stagingRepositories> (or the project's release-jars
   script, if you use one). Maven Central lag can be hours.
2. Record the release at <https://reporter.apache.org/addrelease.html?grails> as
   `micronaut-<version>` on the day the staging repository was released.
3. Flag the GitHub Release as **latest** when it is a final line release.
4. Announce from your `@apache.org` address to `dev@grails.apache.org`,
   `users@grails.apache.org`, and `announce@apache.org`.
5. Move the signed source distribution from
   `dist/dev/grails/micronaut/<version>/` to
   `dist/release/grails/micronaut/<version>/` (manual SVN, PMC credentials).

## Rollback

Abort only **before** the staging repository is released to Maven Central. After that,
ship a new version.

1. Drop the staging repository on repository.apache.org.
2. Cancel the GitHub Actions `Release` run.
3. Delete the GitHub Release and `git push --delete origin v<version>`.
4. If a `[VOTE]` thread exists, reply `[CANCEL][VOTE]` with the reason.

## Appendix: GPG and KEYS

This project uses the shared Grails KEYS file:

<https://dist.apache.org/repos/dist/release/grails/KEYS>

```shell
wget https://dist.apache.org/repos/dist/release/grails/KEYS
gpg --import KEYS
```

```shell
gpg --edit-key <key id>
gpg> trust
gpg> 4
gpg> quit
```

## Appendix: Verification from a container

Official artifacts are built on Ubuntu 24.04 with Liberica 25.0.3. The image in
[`etc/bin/Dockerfile`](etc/bin/Dockerfile) matches that JDK pin:

```shell
docker build -t grails-micronaut:testing -f etc/bin/Dockerfile .
docker run -it --rm -v "$(pwd):/home/groovy/project" grails-micronaut:testing bash
# inside:
cd /home/groovy/project
etc/bin/test-reproducible-builds.sh
```

Anyone bumping the JDK in `.sdkmanrc` or `release.yml` must bump the Dockerfile `FROM`
line as well.

## Appendix: Why the build must be reproducible

ASF policy requires that GitHub-built bits can be reproduced off CI. This repository
keeps the useful Grails reproducibility work and drops the grails-core dual-JDK island
(that existed only because Micronaut lived inside a JDK 21 monorepo).

| Path | Role |
| --- | --- |
| `SOURCE_DATE_EPOCH` in `release.yml` | Pins timestamps to the tagged commit. |
| Root `build.gradle` `formattedBuildDate` / `buildDate` | Feeds javadoc / SBOM dates. |
| `CompilePlugin.configureReproducible` | Archive order, Unix modes, no file timestamps, javadoc `noTimestamp`. |
| `gradle/groovy-compile-configscript.groovy` | Stable annotation member order. |
| `SbomPlugin` | Deterministic CycloneDX JSON. |
| `settings.gradle` | Disables the remote build cache when `SOURCE_DATE_EPOCH` is set. |
| `etc/bin/test-reproducible-builds.sh` | Two clean local builds, hash compare. |
| `etc/bin/generate-build-artifact-hashes.sh` | SHA-512 listing of `build/libs` JARs. |
| `etc/bin/extract-build-artifact.sh` | Unzip two copies of a differing JAR for a directory compare. |
| `etc/bin/Dockerfile` | Linux + Liberica 25.0.3, same as CI. |

Common gotchas: properties files that embed `Date.toString()`, unsorted reflection,
a different JDK patch, and Gradle's remote cache. Do not ship generated `.properties`
with a timestamp comment. Sort anything taken from `Class.getDeclaredMethods()`.
