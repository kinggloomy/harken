# Quick start

**English** | [简体中文](../zh-CN/quickstart.md)

From nothing to "a script calls my own bean" in about five minutes. Assumes a Spring Boot 3
application.

---

## 0. Prerequisites

Do not skip this. The failure mode is wasted time.

| Item | Required | How to check |
|---|---|---|
| JDK | 17 / 21 / 25 | `java -version` |
| Spring Boot | 3.x | version of `spring-boot-starter-parent` |
| Servlet container | 6.0 (Jakarta) | Boot 3's embedded Tomcat 10.1 qualifies |
| Transaction manager | Present (recommended) | `spring-boot-starter-jdbc` / `-jpa` provides one |

Without a transaction manager the application still starts, but **commit and dry run are both
refused** (`HARKEN_NO_TRANSACTION`), because neither case is safe: one cannot be undone, and the
other writes to the database while reporting a rollback.

---

## 1. Add the dependency

```xml
<dependency>
    <groupId>com.harlanking</groupId>
    <artifactId>harken-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'com.harlanking:harken-spring-boot-starter:1.0.0'
```

---

## 2. Minimal configuration

Three lines:

```yaml
harken:
  packages:
    - com.example          # your business packages: bean resolution and the class index
  web:
    users:
      - username: admin
        password: env:HARKEN_ADMIN_PASSWORD
```

Passwords come in three forms:

| Form | Example | Notes |
|---|---|---|
| Plain | `admin123` | Local only |
| Env reference | `env:HARKEN_ADMIN_PASSWORD` | Recommended; a missing variable **fails startup** instead of becoming an empty password |
| PBKDF2 hash | `pbkdf2-sha256$120000$<salt>$<hash>` | See [configuration](configuration.md#passwords) |

> With no `users` configured, Harken generates a temporary account and prints it in the startup
> log. That exists so you can look at the UI; do not take it to any shared environment.

---

## 3. Start and open

```bash
mvn spring-boot:run
# or
java -jar target/your-app.jar --server.port=8081
```

Open <http://127.0.0.1:8081/harken/> and log in.

Three lines in the startup log are worth reading:

```
HARKEN  mode=FULL  restricted=false  ai=disabled
HARKEN  transaction=SpringTransactionBridge  audit=jdbc  guard=enabled
HARKEN  users=1  allowedIps=[127.0.0.1, ::1]
```

---

## 4. Endpoints (don't guess paths)

| Method | Path | Purpose |
|---|---|---|
| GET | `/harken/` | Console page |
| POST | `/harken/auth/login` | Login, body `{"username","password"}`, **not** `/api/login` |
| POST | `/harken/auth/logout` | Log out |
| GET | `/harken/auth/me` | Current identity and remaining session time |
| POST | `/harken/auth/reload` | Reload users and script catalog (invalidates old signatures) |
| POST | `/harken/api/execute` | Run a script (`commit:false` = dry run) |
| POST | `/harken/api/compile` | Compile only, no execution |
| GET | `/harken/api/catalog` | Curated script catalog (`RESTRICTED` mode source) |
| GET | `/harken/api/metadata` | Class index for completion (no methods) |
| GET | `/harken/api/metadata?bean=orderService` | Instance methods of a bean |
| GET | `/harken/api/metadata?type=com.example.MoneyUtil` | Static methods and enum constants |
| GET | `/harken/api/metadata?inst=java.util.List` | Methods of any type (reads `jrt:/`, no `packages` needed) |
| GET | `/harken/api/metadata?jdk=java.util` | Class names in a JDK package |
| GET | `/harken/api/audits` | Recent executions |
| GET | `/harken/api/health` | Actual transaction / audit / guard / AI state |
| POST | `/harken/ai/query` | AI channel (**exists only when `harken.ai.token` is set**) |
| GET | `/harken/ai/capabilities` | Capability manifest for the AI channel |

Full shapes: [HTTP API](http-api.md).

---

## 5. First script

Dry run (`Ctrl+Enter`): execute, then roll back.

```groovy
return orderService.markOne("SO-1001")
```

Status line: `rows=1 rolledBack=true`. **Read the row back**. Otherwise "it rolled back" is just
a claim in a response body:

```groovy
return orderService.statusOf("SO-1001")     // still NEW, not PAID
```

Commit (`Ctrl+Shift+Enter`): in `FULL` mode you must type the environment confirmation word
(the value of `harken.web.environment`), then it really writes.

---

## 6. Using completion

Try all six triggers; that is the fastest way to see how far it goes:

| You type | You get | Based on |
|---|---|---|
| `orderService.` | Instance methods with real parameter names and return types | Bean reflection |
| `MoneyUtil.` | Static methods | Class reflection; no bean needed |
| `Hexagram.` | Enum constants first, then `values()` / `valueOf()` | Class reflection |
| `orderService.topAmount(5).` | `List` methods with generics substituted | Return type per chain segment |
| `list.forEach(m -> { m.` | Methods of the element type | Receiver's element type |
| `Category.` (not imported) | First entry: "add import + complete name" | Index + default-import shadow check |

When it cannot infer, it shows nothing: cross-line chains, or a raw `List` with no type
argument. No answer beats a wrong answer. Details: [scripting](scripting.md).

---

## 7. Five-minute acceptance checklist

```bash
# 1. Page responds (302 to login when not authenticated)
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/harken/

# 2. Log in (keep --noproxy '*' if you have a local proxy)
curl -s --noproxy '*' -c /tmp/hk.jar -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"YOUR_PASSWORD"}' \
  http://127.0.0.1:8081/harken/auth/login

# 3. Self-check: real transaction / audit / guard state
curl -s --noproxy '*' -b /tmp/hk.jar http://127.0.0.1:8081/harken/api/health

# 4. Dry run (Origin is mandatory, otherwise the same-origin check rejects it)
curl -s --noproxy '*' -b /tmp/hk.jar -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:8081' \
  -d '{"script":"return orderService.count()","commit":false}' \
  http://127.0.0.1:8081/harken/api/execute
```

Four greens means you are wired up.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Login returns 404, log says "unknown console endpoint: /login" | Login lives at `/auth/login` | See the endpoint table |
| Login fails, log shows a random password | No `users` configured | Configure `users`, or copy the temp password |
| `HARKEN_CROSS_ORIGIN` | curl without `Origin`, or `Origin` ≠ `Host` | Send the right `Origin` |
| AI endpoint 404 | `harken.ai.token` unset, so the endpoint **is not registered** | Set a token; this is by design |
| Bean is `null` in a script | Class not under `harken.packages` | Add the package |
| Dry run actually wrote | No transaction manager | Add `spring-boot-starter-jdbc`, or declare a `PlatformTransactionManager` |
| Completion shows classes but no methods | Method table not fetched yet | Type the dot again, or check `/api/metadata?bean=` |
| Blank page, `assets/app.js` 404 | Visited `/harken` without the trailing slash | Use `/harken/` |

---

## 9. Three security reminders (yes, even locally)

1. **Do not run `FULL` + `ADMIN` against a production database.** Thirty seconds after adding a
   dependency you can rewrite data. That convenience is also the risk.
2. **Keep `allowed-ips` on loopback.** For remote access use an SSH tunnel
   (`ssh -L 8081:127.0.0.1:8081 user@host`) rather than `*`.
3. **The AI channel's read-only property comes from rollback + a read-only connection**, not from
   a read-only database account. Giving the application a `SELECT`-only account is the cheapest
   hard boundary you can add in production.

Next: [Configuration](configuration.md) · [Security model](security.md)
