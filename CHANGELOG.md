# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [1.0.0] — 2026-10-27

First public release.

### Added

**Engine (`harken-core`)**
- Groovy execution against live beans, wrapped in a `REQUIRES_NEW` transaction that rolls back
  unless a commit is explicitly requested.
- Compile cache keyed by script SHA-256 (LRU, 128 entries).
- Hard timeout with thread interruption injected as an AST transform, so it also fires inside
  loops.
- `ReadOnlyRequest` / `ReadWriteRequest` as a sealed hierarchy — the read-only channel cannot
  carry a commit field because the type has none.
- `HarkenRuntime.builder()` for plain-Java embedding with no Spring on the classpath.
- Script catalogs: classpath, external directory with hot reload, or none.
- Audit store SPI with in-memory ring buffer and composite fan-out.

**Data protection (`harken-jdbc`)**
- `GuardedDataSource` with an affected-row ceiling enforced mid-execution, a table denylist
  supporting globs, and a DDL lock.
- `SqlInspector` — a lightweight lexer that flags write statements without pretending to be a
  full SQL parser.
- Transactional audit store, isolated from the business transaction.

**Web (`harken-web`)**
- Single-page console served by your existing servlet container: no extra port, no separate
  process, no CDN.
- Completion driven by reflection: instance methods, static methods, enum constants, chained
  return types with generic substitution, and closure parameter inference from the receiver's
  element type.
- Automatic import insertion, with a refusal path when the simple name is already bound by a
  different import.
- Dark and light themes, applied before first paint.
- Session auth with PBKDF2, lockout after repeated failures, and constant-time responses for
  unknown users.
- AI channel with bearer token, optional HMAC request signing (timestamp window + nonce replay
  protection), and a physically separate servlet path.

**Spring integration (`harken-spring`, `harken-spring-boot-starter`)**
- Bean resolution, transaction bridge, classpath class index.
- Auto-configuration: one dependency and one property to get a working console.
- Startup banner reporting the real transaction, audit, guard and AI state.
- AI auto-configuration is conditional on a token being present — no token, no endpoint, no code
  path to forget to guard.

**Tooling**
- `harken-demo` (Spring Boot + H2 + real transactions) and `harken-demo-library` (plain Java,
  embedded Tomcat, restricted mode) as runnable references.
- `scripts/pen-test.sh` (19 security cases), `scripts/bench.sh`, `scripts/jdk-matrix.sh`,
  `harken-demo/scripts/http-smoke.sh` (85 assertions).

### Known limitations

Documented rather than hidden — see [the security model](docs/en/security.md) §2:

- No sandbox: scripts hold the application's full authority.
- Runtime-assembled table names are not detected by the denylist.
- The AI channel's read-only property rests on rollback plus a read-only connection, not on a
  read-only database account.
- Masks are regex-based, not a DLP engine.
- Completion refuses to infer across newlines, through overloads, or over raw generic types.

[Unreleased]: https://github.com/kinggloomy/harken/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/kinggloomy/harken/releases/tag/v1.0.0
