# Harken

**English** | [简体中文](README.zh-CN.md)

[![build](https://github.com/kinggloomy/harken/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kinggloomy/harken/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.harlanking/harken-spring-boot-starter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.harlanking/harken-spring-boot-starter)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](#requirements)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](#requirements)

Harken is a web console for a running Java application. You write a Groovy snippet in the browser, call one of your own Spring beans, and get the return value, the affected row count and the log back straight away. Nothing is written to the database by default: the script runs inside its own transaction and rolls back when it finishes. It commits only when you explicitly ask it to.

It is built for questions like "why is this order still `NEW` in production". The usual way to answer that is to ssh in and run a throwaway `main`, add a temporary endpoint and redeploy, or export the rows and pass them around in chat. Harken does the same work from a page your application already serves, using the login, IP allowlist and transaction manager you already have.

## What it does

- **No redeploy.** Run Groovy against live beans, with completion that knows your classpath.
- **Dry run by default.** Execution is wrapped in `REQUIRES_NEW`; without a commit, it rolls back.
- **Every run is recorded.** Who, when, which script, whether it committed, why it failed. Rejected attempts included.
- **AI can read, not write.** A separate endpoint takes a request type that has no `commit` field.
- **Writes are bounded.** Row ceiling, table denylist and DDL rejection, checked while the statement runs rather than up front.
- **Two shapes.** A Spring Boot starter, or a plain `main()` with `HarkenRuntime.builder()`. `harken-core` has no Spring on its classpath.

## What it does not do

- **It is not a sandbox.** Scripts run with your application's authority: same JVM, same classloader, same database credentials. If you would not give someone ssh access to the box, do not give them commit rights in Harken. See the [security model](docs/en/security.md).
- **It is not an ops tool.** No deployment, no config management, no dashboards.
- **It is not an MCP server, not multi-tenant, not a scripting IDE.** These are non-goals, not backlog items.
- **Not recommended against a production database in `FULL` mode.** Staging is fine. For production, use `RESTRICTED` mode with a reviewed script directory, or the read-only channel.

## Quick start

**1. Add the dependency**

For a Spring Boot application, one dependency:

```xml
<dependency>
    <groupId>com.harlanking</groupId>
    <artifactId>harken-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

The same coordinates in Gradle:

```groovy
implementation 'com.harlanking:harken-spring-boot-starter:1.0.0'
```

Without Spring, take the engine, the servlet layer and the guards separately, then build the runtime with `HarkenRuntime.builder()`. `harken-demo-library` is a working example of this setup, running on embedded Tomcat.

```xml
<dependency>
    <groupId>com.harlanking</groupId>
    <artifactId>harken-core</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>com.harlanking</groupId>
    <artifactId>harken-web</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>com.harlanking</groupId>
    <artifactId>harken-jdbc</artifactId>
    <version>1.0.0</version>
</dependency>
```

All five library modules are on Maven Central under `com.harlanking`, published together at one version: [central.sonatype.com/artifact/com.harlanking](https://central.sonatype.com/artifact/com.harlanking/harken-spring-boot-starter).

**2. Set the bean packages and a login**

```yaml
harken:
  packages:
    - com.example.order
    - com.example.billing
  web:
    users:
      - username: admin
        password: env:HARKEN_ADMIN_PASSWORD
```

**3. Start the app and open `http://127.0.0.1:8080/harken/`**

```groovy
// dry run: it really executes, then rolls back
return orderService.markOne("SO-1001")
```

The status bar reports what actually happened: `rows=1 rolledBack=true`. Read the row back and it is still `NEW`, which is the rollback being real rather than a sentence in a response body.

Full walkthrough: [Quick start](docs/en/quickstart.md).

## The console

The UI is a single page served by the same servlet container. No extra port, no separate process, no Node build step at runtime.

- **Completion that knows your classpath.** Type `orderService.` and you get the real method signatures read from the bean. Type `Hexagram.` and the enum constants come first. `list.forEach(m -> { m.` infers the type of `m` from the receiver's element type and substitutes generics, so you get `Map` methods and not `E` methods.
- **Automatic imports.** For a name that is not imported yet, the first suggestion inserts the import and completes the name in one undoable step. If the simple name is already taken by a different import, it refuses instead of guessing.
- **Dark and light themes.** Remembered per browser and applied before first paint.

## The AI channel

`POST /harken/ai/query` takes a `ReadOnlyRequest`. That type has no field that would make the request write, and no property relaxes it, so a later refactor cannot quietly undo the guarantee. Without `harken.ai.token` the endpoint is not registered at all: there is no code path to bypass, rather than a code path that refuses.

HMAC signing is optional (`harken.ai.signing-secret`). Rotate the secret with `POST /harken/auth/reload`; old signatures stop working immediately.

## Requirements

| | |
|---|---|
| Java | 17, 21 or 25 (all three exercised on Linux and Windows) |
| Spring Boot | 3.x (optional; the plain-Java path needs no Spring) |
| Servlet | 6.0 (Jakarta), provided by your container |
| Database | Any `DataSource`. The JDBC guards use plain JDBC |
| Browser | Any modern browser. The console bundles CodeMirror 5 locally, no CDN |

## Modules

| Artifact | What it does | Spring-free |
|---|---|---|
| `harken-core` | Compile, execute, bind, audit. The engine. | ✅ |
| `harken-jdbc` | Row ceiling, table denylist, transactional audit store. | ✅ |
| `harken-web` | Servlet layer: console API plus a separate AI API. | ✅ |
| `harken-spring` | Bean resolver, transaction bridge, classpath index. | — |
| `harken-spring-boot-starter` | Auto-configuration. One dependency, one property. | — |

The repo also has two runnable samples: `harken-demo` (Spring Boot + H2) and `harken-demo-library` (plain Java + embedded Tomcat). They are not published to Maven Central; they are samples, not libraries.

## Documentation

| | English | 简体中文 |
|---|---|---|
| Quick start | [docs/en/quickstart.md](docs/en/quickstart.md) | [docs/zh-CN/quickstart.md](docs/zh-CN/quickstart.md) |
| Configuration | [docs/en/configuration.md](docs/en/configuration.md) | [docs/zh-CN/configuration.md](docs/zh-CN/configuration.md) |
| Scripting & completion | [docs/en/scripting.md](docs/en/scripting.md) | [docs/zh-CN/scripting.md](docs/zh-CN/scripting.md) |
| HTTP API | [docs/en/http-api.md](docs/en/http-api.md) | [docs/zh-CN/http-api.md](docs/zh-CN/http-api.md) |
| Security model | [docs/en/security.md](docs/en/security.md) | [docs/zh-CN/security.md](docs/zh-CN/security.md) |
| Deployment | [docs/en/deploy.md](docs/en/deploy.md) | [docs/zh-CN/deploy.md](docs/zh-CN/deploy.md) |
| Performance | [docs/en/performance.md](docs/en/performance.md) | [docs/zh-CN/performance.md](docs/zh-CN/performance.md) |

API reference (Javadoc): <https://harken.harlanking.com/api/1.0.0/>

## Build from source

```bash
git clone https://github.com/kinggloomy/harken.git
cd harken
mvn clean install            # builds 7 modules and runs the tests
java -jar harken-demo/target/harken-demo-*.jar
# console: http://127.0.0.1:8080/harken/
```

Requires JDK 17+ and Maven 3.6+. A wrong JDK fails the build with an explanation instead of a pile of confusing compile errors.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first; it lists a few architectural rules that will get a patch rejected, the main one being that `harken-core` must never gain a Spring dependency.

For security issues, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## License

[Apache License 2.0](LICENSE)
