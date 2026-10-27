# Releasing to Maven Central

Everything here is one-time setup plus a five-command release. Nothing in this file contains
credentials; they live in `~/.m2/settings.xml` on the release machine.

---

## 1. One-time setup

### 1.1 Namespace

`groupId` is `com.harlanking`. The namespace must be **verified** on
[central.sonatype.com](https://central.sonatype.com) — for a custom domain, that is a DNS TXT
record on `harlanking.com`. Verified namespaces show a green check on the Namespaces page; until
then, deployments fail validation with "namespace not registered".

### 1.2 GPG key

Central requires a signature for every artifact.

```bash
gpg --full-generate-key          # RSA 4096, no expiry is fine for a project key
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

The public key must reach a keyserver, otherwise the validation step reports a missing signature.

### 1.3 `~/.m2/settings.xml`

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
    <server>
      <id>gpg</id>
      <passphrase>GPG_KEY_PASSPHRASE</passphrase>
    </server>
  </servers>
</settings>
```

- `central` is the id referenced by `<publishingServerId>` in the root `pom.xml`. Generate the
  token on the portal under *Account → Generate User Token*; the username is a short token id, not
  your login email.
- `gpg` is what the `maven-gpg-plugin` reads the passphrase from. Combined with
  `--pinentry-mode loopback` in the POM, the build never stops to prompt.

Alternatively export `MAVEN_GPG_PASSPHRASE` instead of storing it in a file.

---

## 2. Release procedure

### 2.1 Pick the version

Central rejects `-SNAPSHOT` versions. Development stays on `1.0.0-SNAPSHOT`; the release bumps it:

```bash
mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
```

Then, after the release is published, move to the next development version:

```bash
mvn versions:set -DnewVersion=1.0.1-SNAPSHOT -DgenerateBackupPoms=false
```

### 2.2 Update the changelog

Move everything under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) into a new version
heading with today's date, and add the compare links at the bottom.

### 2.3 Verify the build without deploying

```bash
mvn clean verify -Prelease
```

This produces, per module, `-sources.jar`, `-javadoc.jar`, `.asc` signatures and checksums. If
signing fails here, fix it now rather than after the upload.

### 2.4 Deploy

```bash
mvn clean deploy -Prelease
```

What happens:

1. All seven modules build and are tested.
2. `harken-demo` and `harken-demo-library` are excluded by `excludeArtifacts` — they are samples,
   and `harken-demo` is repackaged by the Spring Boot plugin, so it would be a broken dependency.
3. A bundle lands on the Central Portal in **validated** (not published) state, because
   `autoPublish=false`.
4. The command blocks until validation finishes and prints the deployment id.

### 2.5 Publish

Open <https://central.sonatype.com/publishing/deployments>, check the artifact list — it should be:

```
com.harlanking:harken-parent:1.0.0            pom
com.harlanking:harken-core:1.0.0              jar + sources + javadoc
com.harlanking:harken-jdbc:1.0.0              jar + sources + javadoc
com.harlanking:harken-web:1.0.0               jar + sources + javadoc
com.harlanking:harken-spring:1.0.0            jar + sources + javadoc
com.harlanking:harken-spring-boot-starter:1.0.0  jar + sources + javadoc
```

Then press **Publish**. A validated deployment can still be dropped; a published one cannot.

Indexing to `repo.maven.apache.org` takes between a few minutes and a couple of hours.

### 2.6 Tag

```bash
git tag -a v1.0.0 -m "1.0.0"
git push origin v1.0.0
```

The POM's `<scm><tag>` is `v${project.version}`, so the tag must match that shape.

---

## 3. If something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `namespace not registered` | Namespace not verified | Verify on the portal; DNS TXT for a custom domain |
| `Javadocs must be provided but not found` | A module without `src/main/java`, or the javadoc goal skipped | Confirm `-Prelease` is active; empty modules should be excluded via `excludeArtifacts` |
| `Invalid signature` / `Missing signature` | Public key never uploaded, or passphrase not found | `gpg --send-keys`, check the `gpg` server block |
| Build hangs waiting for input | Missing `--pinentry-mode loopback` | Already configured in the parent POM; check you did not override `gpgArguments` |
| `publishingServerId` unknown | No `<server>` with id `central` | Add it to `settings.xml` |
| Validation fails on one artifact | Usually a POM missing `name`, `description`, `url`, `licenses`, `developers` or `scm` | Those are all inherited from the parent; check the module's `<parent>` block |
| Uploaded the wrong thing | `autoPublish=false` saved you | Drop the deployment on the portal |

A published release **cannot be deleted**. If a broken version reaches Central, publish a patch
version and mark the bad one as deprecated in the changelog — that is the only remedy the
ecosystem offers.

---

## 4. Snapshots (optional)

Snapshots do not go through the Portal release flow. Add to the root POM if you want them:

```xml
<distributionManagement>
  <snapshotRepository>
    <id>central</id>
    <url>https://central.sonatype.com/repository/maven-snapshots</url>
  </snapshotRepository>
</distributionManagement>
```

Then `mvn deploy -DskipTests` publishes a snapshot. Snapshots must be **enabled per namespace**
in the portal's namespace settings, or uploads are rejected. This project does not currently use
them — `mvn install` covers local iteration and the demos.
