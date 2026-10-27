# Configuration

**English** | [简体中文](../zh-CN/configuration.md)

Every key lives under `harken.*`. This page is ordered by "what you will actually change", not
alphabetically.

---

## Minimum viable config

```yaml
harken:
  packages:
    - com.example
  web:
    users:
      - username: admin
        password: env:HARKEN_ADMIN_PASSWORD
```

That is enough to run. Everything else has a default, and every default sits on the **more
conservative** side.

---

## Top level

| Key | Default | Notes |
|---|---|---|
| `harken.enabled` | `true` | Master switch. Off registers nothing |
| `harken.packages` | `[]` | Scanned packages (bean index, class index). **Empty = nothing found** |
| `harken.timeout-ms` | `10000` | Wall-clock limit per execution; interrupts the thread |
| `harken.max-script-length` | `102400` | Script size cap (100 KB) |
| `harken.result-max-bytes` | `8192` | Serialized return value cap, truncated and flagged |
| `harken.output-max-bytes` | `32768` | Captured `println` cap |
| `harken.require-transaction-for-ai` | `true` | Refuse the AI channel when no transaction is available |
| `harken.print-banner` | `true` | Print the three status lines at startup |

> A wrong `harken.packages` (say, an old company prefix) does not error. It silently matches
> zero beans, and the only symptom is "every bean is `null` in scripts". The startup banner
> prints the match count, so check it.

---

## `harken.web.*` (the console)

| Key | Default | Notes |
|---|---|---|
| `harken.web.enabled` | `true` | Off registers no console endpoints |
| `harken.web.base-path` | `/harken` | Prefix for console and API |
| `harken.web.allowed-ips` | `127.0.0.1, ::1, ::ffff:127.0.0.1` | Exact IP / IPv4 CIDR / `*`. **Empty = deny all** |
| `harken.web.session-idle-minutes` | `30` | Idle timeout |
| `harken.web.session-max-hours` | `8` | Absolute session cap |
| `harken.web.max-login-failures` | `5` | Failures before lockout |
| `harken.web.lock-minutes` | `15` | Lockout duration |
| `harken.web.generate-password-if-no-users` | `true` | Generate a temp account and log it |
| `harken.web.max-body-bytes` | see source | Request body cap |
| `harken.web.trust-forwarded-for` | `false` | Only behind a proxy you fully control |
| `harken.web.environment` | `""` | Environment label, also the commit confirmation word |
| `harken.web.mode` | `FULL` | `FULL` = free editing; `RESTRICTED` = catalog scripts only, no editor |

### What the two modes actually change

| | `FULL` | `RESTRICTED` |
|---|---|---|
| In-page editor | Yes | No, catalog list only |
| Executable content | Any Groovy | Only scripts already in the catalog directory |
| Commit | Needs the confirmation word | Same |
| Intended for | Local / staging | Production |

In `RESTRICTED` the catalog comes from `harken.catalog.*`, and each script carries a risk level.
Anything above `harken.catalog.max-risk` does not appear in the list.

### Passwords

```yaml
password: admin123                          # plain, local only
password: env:HARKEN_ADMIN_PASSWORD         # env reference, recommended
password: 'pbkdf2-sha256$120000$...$...'    # hash
```

Generate a hash (the `harken-web` jar is in your local repository):

```bash
java -cp ~/.m2/repository/com/harlanking/harken-web/1.0.0/harken-web-1.0.0.jar \
  com.harlanking.harken.web.auth.PasswordHash 'your-password'
```

The `env:` form fails startup when the variable is missing, instead of degrading to an empty
password.

### Roles

| Role | Can do |
|---|---|
| `OPERATOR` | Dry run (rollback), read audits |
| `ADMIN` | Everything above, plus commit and config reload |

---

## `harken.ai.*` (the AI channel)

| Key | Default | Notes |
|---|---|---|
| `harken.ai.enabled` | `true` | The token below decides whether the endpoint exists |
| `harken.ai.token` | `""` | **Empty = endpoint not registered**, not "exists but refuses" |
| `harken.ai.signing-secret` | `""` | Non-empty = **every** AI request must be HMAC-signed |
| `harken.ai.signature-window-seconds` | `60` | Clock-skew window |

```yaml
harken:
  ai:
    token: env:HARKEN_AI_TOKEN
    signing-secret: env:HARKEN_AI_SIGNING_SECRET
```

There is deliberately **no** `ai.read-only` switch. Read-only comes from `ReadOnlyRequest`
having no `commit` field. A read-only guarantee you can turn off is not a guarantee.

Signing details: [HTTP API](http-api.md#ai-channel).

---

## `harken.guard.*` (write guard rails)

| Key | Default | Notes |
|---|---|---|
| `harken.guard.enabled` | `true` | Off means Harken never touches your JDBC layer |
| `harken.guard.max-affected-rows` | `0` | Row ceiling. **`0` = unlimited** |
| `harken.guard.deny-tables` | `[]` | Denylist, supports `secret_*` / `*_log` / `*` |
| `harken.guard.deny-ddl` | `false` | Reject DDL |

Recommended production values:

```yaml
harken:
  guard:
    max-affected-rows: 100        # 0 is too loose: one UPDATE without WHERE is the whole table
    deny-tables:
      - "*_ledger"
      - "settlement_*"
    deny-ddl: true
```

The guard fires **mid-execution**, not by inspecting SQL text upfront. `GuardedDataSource`
wraps the data source in a dynamic proxy, counts rows on every `executeUpdate`, and throws
`HARKEN_ROW_LIMIT` the moment the ceiling is crossed. So `markAllPaid()` touching five rows with
a ceiling of two is aborted *while it is running*, not "skipped".

Rejected executions are audited too. That is intentional: the record of who touches a critical
table is exactly the record of failures.

---

## `harken.catalog.*` (curated scripts)

| Key | Default | Notes |
|---|---|---|
| `harken.catalog.provider` | `none` | `classpath` / `file` / `none` |
| `harken.catalog.file` | `""` | Directory for `file` mode |
| `harken.catalog.allow-hot-reload` | `true` | Re-read on change |
| `harken.catalog.max-risk` | `READ` | Highest visible risk level |

```yaml
harken:
  catalog:
    provider: file
    file: /etc/yourapp/harken-scripts
    max-risk: WRITE          # READ / WRITE / DDL
```

Script headers are parsed from comments; only this shape is recognized:

```groovy
// @name Find order
// @risk READ
// @desc Look one order up by number. Changes nothing.
return orderService.byNo("SO-1001")
```

---

## `harken.audit.*` (audit storage)

| Key | Default | Notes |
|---|---|---|
| `harken.audit.memory.enabled` | `true` | In-process ring buffer, last 200 entries |
| `harken.audit.memory.capacity` | `200` | Entry count |
| `harken.audit.jdbc.enabled` | `false` | Persist to a table |
| `harken.audit.jdbc.table` | `harken_audit` | Table name, schema prefix allowed |
| `harken.audit.jdbc.auto-create` | `true` | Auto-DDL; production usually pre-creates and disables |

Both enabled gives a `CompositeAuditStore`: the memory copy for fast paging in the UI, the JDBC
copy for after-the-fact investigation.

**Audit writes happen outside the transaction.** The execution that rolled back is precisely the
one you most need a record of, so it cannot share that transaction.

---

## Environment overrides

Standard Spring Boot precedence applies:

```
command line --harken.web.mode=RESTRICTED
env var      HARKEN_WEB_MODE=RESTRICTED
application-prod.yml
application.yml
```

When a setting "does not take effect", check `/harken/api/health` first. It reports the state
read at runtime, not an echo of your config file.

---

## Full example (production-oriented)

```yaml
harken:
  packages:
    - com.example.order
    - com.example.billing
  timeout-ms: 5000
  result-max-bytes: 4096

  web:
    allowed-ips:
      - 10.20.0.0/24          # bastion subnet, not *
    trust-forwarded-for: true # exactly one nginx in front, ours
    environment: prod
    mode: RESTRICTED
    session-idle-minutes: 15
    users:
      - username: ops
        password: env:HARKEN_OPS_PASSWORD
        role: OPERATOR
      - username: dba
        password: 'pbkdf2-sha256$120000$...$...'
        role: ADMIN

  catalog:
    provider: file
    file: /etc/yourapp/harken-scripts
    max-risk: READ

  guard:
    max-affected-rows: 50
    deny-tables: ["*_ledger", "settlement_*"]
    deny-ddl: true

  audit:
    jdbc:
      enabled: true
      auto-create: false      # DBA owns the table
```

Note there is no `ai` section. In production, leaving the AI channel off means the endpoint does
not exist at all, which is the calmest state available.
