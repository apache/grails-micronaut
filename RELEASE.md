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
and runs `apache/grails-github-actions/pre-release@asf`. That action commits
`projectVersion=<version>` in `gradle.properties` and moves the tag to that commit.
The staged artifacts and source zip come from this updated tag, not the original commit.
After promotion, `post-release@asf` opens a merge-back PR with the next SNAPSHOT version.
Neither action changes `grailsVersion`; no extra `RELEASE_SCRIPT_PATH` is needed here.

Milestones and RCs go through the same process as a final release.

## 1. Prerequisites

- The initial tag points at the code to release; pre-release adds the version commit. No SNAPSHOT dependencies; Apache
  [release policy](https://www.apache.org/legal/release-policy.html) forbids them.
- `projectVersion` is this repository's version. Pre-release writes the release version
  into the tagged commit. `grailsVersion` is the released Grails line (8.0.0) imported by the
  BOMs and used by the Grails Gradle plugins. Do not point `grailsVersion` at a snapshot.
- JDK is pinned in [`.sdkmanrc`](.sdkmanrc) (`25.0.3-librca`). CI and
  [`etc/bin/Dockerfile`](etc/bin/Dockerfile) must use the same Liberica patch.
- Every file referenced by `LICENSE` and `NOTICE` must be present in the source
  package and, where applicable, the JARs. The verification suite rejects missing
  referenced license texts even when RAT passes.
- Secrets and variables:

  | Name | Purpose |
  | --- | --- |
  | `GRAILS_GPG_KEY` | Private key imported for signing. |
  | `GPG_KEY_ID` / `SIGNING_KEY` | Key id passed to Gradle signing. |
  | `NEXUS_STAGE_DEPLOYER_USER` | Staging username. |
  | `NEXUS_STAGE_DEPLOYER_PW` | Staging password. |
  | `STAGING_PROFILE_ID` | Nexus staging profile. |
  | `STAGING_URL` | Repository variable for the staging API URL. |
  | `GRAILS_DEVELOCITY_ACCESS_KEY` | Gradle Develocity (optional for a local rebuild). |
  | `SVC_DIST_GRAILS_USERNAME` | Service account for staging the source zip in SVN `dev`. |
  | `SVC_DIST_GRAILS_PASSWORD` | SVN staging service account password. |

  Multi-line secrets such as the GPG key need a trailing newline.

- The `release` / `close` / `docs` environments in [`.asf.yaml`](.asf.yaml) list reviewers
  (GitHub caps private-group approvers; at most 6 named users). Staging runs without
  approval. The post-vote jobs use `release` for manual promotion instructions,
  `docs` for the publication and announcement checkpoint, and `close` for the merge-back PR.
  There is no documentation build or site deployment in this repository.
- GitHub must allow the workflow token to update the release tag, create a merge-back
  branch and open pull requests. Promotion of Nexus and SVN remains a **manual** PMC step
  using personal ASF credentials; the SVN service account can write only to `dev`.

## 2. Cut the release

1. Open <https://github.com/apache/grails-micronaut/releases> and **Draft a new release**.
2. Tag `v<version>` (for example `v8.0.0-M1`) targeting `8.0.x` (or the maintenance branch).
3. Generate release notes, then edit them.
4. Check **Set as a pre-release** while the candidate is under vote, including a final version.
   Do not mark it latest until promotion succeeds.
5. **Publish release**. That starts the `Release` workflow.

## 3. What the `publish` job does

The job in [`.github/workflows/release.yml`](.github/workflows/release.yml):

1. Checks out the tag with full history.
2. Sets `SOURCE_DATE_EPOCH` from the last commit time and `touch`es the tree to that epoch
   so archive and documentation timestamps match a local rebuild.
3. Uses Liberica JDK `25.0.3`.
4. Runs pre-release to commit `projectVersion` and update the tag, imports `GRAILS_GPG_KEY`,
   then runs `./gradlew build rat --no-build-cache` so the staged bits are tested.
5. Initializes one Sonatype staging repository with description `grails-micronaut:<version>`.
6. Runs `findSonatypeStagingRepository publishToSonatype aggregateChecksums aggregatePublishedArtifacts`
   with `-x initializeSonatypeStagingRepository`, then finds and closes that same repository
   in a separate invocation, also excluding initialization. This avoids an orphan empty repository.
7. Writes `build/BUILD_DATE.txt`, `build/CHECKSUMS.txt`, and `build/PUBLISHED_ARTIFACTS.txt`
    and attaches them temporarily to the GitHub Release. The build date is the epoch recorded
    before pre-release, matching grails-core. Use `BUILD_DATE`, not the moved tag's timestamp,
    when rebuilding the voted artifacts.

The staging repository is **closed**, not released. Do not promote it until verification
and the vote succeed.

## 3.1 Stage the signed source distribution

ASF [release policy](https://www.apache.org/legal/release-policy.html) requires every
release to include a **signed source package**. Maven JARs are convenience binaries.
The official vote artifact is the source zip, staged under
`https://dist.apache.org/repos/dist/dev/grails/micronaut/<version>/sources/`
before the vote starts. The `source` and `upload` jobs now do this automatically:

1. Check out the exact post-pre-release commit recorded by `publish`.
2. Remove `.git`, `.github`, agent worktrees, `KEYS`, and the root and `build-logic`
   Gradle wrapper JARs, properties and launch scripts, including `gradlew.bat`.
3. Include `CHECKSUMS`, `PUBLISHED_ARTIFACTS` and `BUILD_DATE` from the publish job.
   The manifests include the four coordinates' JARs, POMs and Gradle module metadata.
4. Normalize source timestamps and create `apache-grails-micronaut-<version>-src.zip`
   with one top-level `grails-micronaut/` directory. Sign it and generate its SHA-512 file.
5. Attach the zip, `.asc` and `.sha512` to the GitHub Release and remove the temporary
   manifest assets, which are now inside the signed zip.
6. Upload the same three files to SVN `dev/grails/micronaut/<version>/sources/`,
   attach `DIST_SVN_REVISION.txt` to the GitHub Release, and print the PMC vote email.

The upload refuses to overwrite an existing candidate directory. Abort the candidate
before staging a replacement. After the vote passes, a PMC member promotes the directory
with personal credentials. Nothing is promoted by the staging service account.

## 4. Verify staged artifacts

Use the automated suite from a checkout of the release tag with GNU coreutils, GPG,
curl, unzip, and the exact Liberica JDK and Gradle versions in `.sdkmanrc`:

```shell
export PATH="$PWD:$PWD/etc/bin:$PATH"
verify.sh v8.0.0 /tmp/grails-micronaut-verify
```

The download location is scratch space, not the repository. The suite compares the GitHub
and SVN files, imports the official Grails KEYS into a temporary GPG home, verifies the
source signature and contents, and checks signatures and checksums for all four coordinates.
It rejects SNAPSHOT references in POMs and module metadata and checks JAR licenses and SBOMs.
It then runs RAT, builds and tests the extracted source with `--no-build-cache`, and compares
every published checksum, including BOM metadata. Any failure exits nonzero.

The zip deliberately has no wrappers. Unlike core's multi-build `gradle-bootstrap` setup,
this build can use an external `gradlew` on `PATH` (the container and verification workflow
provide one) or the matching installed Gradle directly with `-p <extracted-source>`.
No wrapper files are copied into the source tree, and `build-logic` is an included build
that does not need its own wrapper. For a manual build with installed Gradle:

```shell
cd /tmp/grails-micronaut-verify/grails-micronaut
export SOURCE_DATE_EPOCH="$(cat BUILD_DATE)"
export TEST_BUILD_REPRODUCIBLE=true
gradle build rat --no-build-cache
```

The individual `verify-*.sh` scripts can also be run separately. Use
`reset-verify.sh v8.0.0 /tmp/grails-micronaut-verify` before retrying in the same scratch
directory; it prompts before deleting the extracted tree and its verification results.
Alternatively dispatch [Release - Verify Troubleshooting](.github/workflows/release-verify.yml)
with the candidate tag. This workflow does not replace independent PMC verification.

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

Use `CHECKSUMS` and `PUBLISHED_ARTIFACTS` inside the signed source zip:

1. Import <https://dist.apache.org/repos/dist/release/grails/KEYS>.
2. Download each JAR (and `.asc`) from the Nexus staging repository listed in
   `PUBLISHED_ARTIFACTS` (use `GRAILS_REPO_URL` to select a specific staging repository).
3. `gpg --verify` each signature.
4. Compare SHA-512 to `CHECKSUMS`.
5. Rebuild locally with the same `SOURCE_DATE_EPOCH` as `BUILD_DATE` and
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
The `upload` job prints the adapted core template from `.github/vote_templates/staged.txt`.
Replace `<Thread Link>`, review the text, and send it yourself. No workflow sends email.

## 6. Promote

After the vote passes:

1. Approve the `release` environment only after the minimum 72-hour vote passes.
   The job prints the result email and manual promotion commands. Review and send the result.
2. Release the closed Nexus staging repository on
   <https://repository.apache.org/#stagingRepositories>, or run:

   ```shell
   .github/scripts/releaseJarFiles.sh grails-micronaut:8.0.0 <ASF_USER>
   .github/scripts/releaseDistributions.sh v8.0.0 micronaut <ASF_USER>
   ```

   Both scripts prompt for personal ASF credentials and confirmation of the vote.
   The second moves only `dev/grails/micronaut/<version>` to
   `release/grails/micronaut/<version>` using SVN. It does not delete earlier releases.
3. Record `MICRONAUT-<version>` at <https://reporter.apache.org/addrelease.html?grails>
   with the promotion date. Wait for Maven Central and download mirrors to catch up.
4. Approve the `docs` environment for the announcement checkpoint after promotion.
   This repo has no separate documentation distribution or deployment job. Review the printed
   announcement, replace the template placeholders, and send from your `@apache.org` address
   to `dev@grails.apache.org`, `users@grails.apache.org`, and `announce@apache.org`.
5. Approve `close`. The ASF post-release action closes the matching milestone, creates
   `merge-back-<version>` from the released tag, bumps only `projectVersion` to the next
   SNAPSHOT, and opens a PR into the release's maintenance branch. Merge with a merge commit,
   not squash or rebase, so the tag becomes part of branch history. Clear the GitHub pre-release
   flag for a final release and mark latest only when appropriate for the release line.

If closing fails, run [Release - Close](.github/workflows/release-close.yml) from the matching
maintenance branch with the released version. It is protected by `close` and does not promote
artifacts. Inspect the original log first. If the token cannot push workflow changes or open
a PR, create the merge-back branch from the tag, update `projectVersion` to the next SNAPSHOT,
and open the merge-back PR with your own credentials. Do not change `grailsVersion`.

## Rollback

Abort only **before** the staging repository is released to Maven Central. After that,
ship a new version.

Run [Release - Abort Release](.github/workflows/release-abort.yml) with the candidate tag.
It requires `release` approval and a GitHub release still marked pre-release, and it refuses
to run if the version already exists in the Nexus releases repository or SVN `release`.
The workflow cancels the candidate's release runs and waits for them to stop, drops the Nexus
repository by description `grails-micronaut:<version>`, removes only
`dist/dev/grails/micronaut/<version>`, and deletes the GitHub Release and tag. Any SVN error
other than a missing path stops it before the release and tag are deleted. If a `[VOTE]`
thread exists, reply `[CANCEL][VOTE]` with the reason.

To drop only a failed or orphan staging repository, use
[Release - Drop Nexus Staging](.github/workflows/release-drop-staging.yml) with its Nexus ID.
It also requires `release` approval and checks the configured staging profile before dropping.
It does not delete the GitHub Release, tag or SVN files.

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
# inside the container:
cd /home/groovy/grails-verify
verify.sh v8.0.0 .
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

