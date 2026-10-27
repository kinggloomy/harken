# HTTP API

**English** | [简体中文](../zh-CN/http-api.md)

Every endpoint hangs off `harken.web.base-path` (default `/harken`). Requests and responses are
JSON/UTF-8.

---

## Conventions

**Same-origin check.** Apart from GETs and login, every write request must carry `Origin` matching
`Host`. Mismatch returns `HARKEN_CROSS_ORIGIN`. This is not the whole CSRF story, but it stops
"another page POSTs for you".

**Error shape.** Failures always look like this, and the HTTP status carries meaning too:

```json
{
  "ok": false,
  "errorType": "HARKEN_ROW_LIMIT",
  "error": "3 affected rows exceeds the ceiling of 2; aborted at row 3",
  "traceId": "a1b2c3d4e5f6"
}
```

**`errorType` values.** Clients branch on this, not on the message text.

| Value | Meaning |
|---|---|
| `COMPILATION` | Groovy compile failure (also `line` / `column`) |
| `HARKEN_ROW_LIMIT` | Affected-row ceiling exceeded |
| `HARKEN_TABLE_FORBIDDEN` | Table denylist hit |
| `HARKEN_DDL_FORBIDDEN` | DDL rejected |
| `HARKEN_NO_TRANSACTION` | No transaction manager; commit and dry run both refused |
| `HARKEN_TIMEOUT` | Exceeded `harken.timeout-ms` |
| `HARKEN_SCRIPT_TOO_LONG` | Exceeded `max-script-length` |
| `HARKEN_CROSS_ORIGIN` | Origin missing or mismatched |
| `HARKEN_FORBIDDEN` | Insufficient role (`OPERATOR` attempting commit) |
| `HARKEN_AI_UNAUTHORIZED` | AI token missing or invalid |
| `HARKEN_SIGNATURE_*` | `MISSING` / `INVALID` / `STALE` / `REPLAY` |

---

## Authentication

```bash
curl -s --noproxy '*' -c /tmp/hk.jar \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"***"}' \
  http://127.0.0.1:8080/harken/auth/login
```

```json
{
  "ok": true,
  "username": "admin",
  "role": "ADMIN",
  "canCommit": true,
  "idleMinutes": 30,
  "maxHours": 8
}
```

The session is a cookie (`HttpOnly`, `SameSite=Strict`). Pass `-b /tmp/hk.jar` afterwards.

| Endpoint | Purpose |
|---|---|
| `POST /auth/logout` | Log out |
| `GET /auth/me` | Identity, role, remaining time |
| `POST /auth/reload` | Reload users and catalog. **Rotating a secret invalidates old signatures immediately** |

After `max-login-failures` consecutive failures the account locks for `lock-minutes`. A nonexistent
user still runs one PBKDF2 hash, so the timing matches "wrong password". Otherwise the response
time leaks which usernames exist.

---

## Execution

### `POST /api/execute`

```bash
curl -s --noproxy '*' -b /tmp/hk.jar \
  -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:8080' \
  -d '{"script":"return orderService.markOne(\"SO-1001\")","commit":false}' \
  http://127.0.0.1:8080/harken/api/execute
```

```json
{
  "ok": true,
  "result": true,
  "output": "",
  "durationMs": 12,
  "rolledBack": true,
  "affectedRows": 1,
  "truncated": false,
  "channel": "human",
  "traceId": "a1b2c3d4e5f6"
}
```

| Field | Meaning |
|---|---|
| `ok` | Whether execution succeeded (`false` when a guard rejected it) |
| `result` | The script's return value, JSON-ified |
| `output` | Captured `println` text |
| `error` / `errorType` | Failure message and category |
| `durationMs` | End-to-end duration |
| `rolledBack` | **Whether it actually rolled back** |
| `affectedRows` | Accumulated affected rows |
| `truncated` | Whether the result or output was cut |
| `hint` | Optional next-step suggestion |
| `channel` | `human` / `ai` |
| `traceId` | Correlates with the audit record |

`commit:true` requires `ADMIN`, and in `FULL` mode also the confirmation word (matching
`harken.web.environment`).

### `POST /api/compile`

Compiles without executing, without touching the database.

```json
{ "ok": false, "errorType": "COMPILATION", "error": "unexpected token: }", "line": 3, "column": 12 }
```

---

## Metadata (for completion)

### `GET /api/metadata`

Everything at once: bean names, package → class names, simple name → FQCN. **No methods**
(method tables for hundreds of classes would be huge; they are fetched on demand). Roughly 24 KB
for 800 classes.

### `GET /api/metadata?bean=orderService`

```json
{
  "ok": true,
  "kind": "bean",
  "type": "com.example.order.OrderService",
  "methods": [
    { "name": "count", "returns": "long", "params": [], "static": false },
    { "name": "markOne", "returns": "boolean",
      "params": [{ "type": "java.lang.String", "name": "orderNo" }], "static": false }
  ]
}
```

### `GET /api/metadata?type=com.example.MoneyUtil`

Static methods, enum constants and type variables all come through this one parameter.

### `GET /api/metadata?inst=java.util.List`

**Instance** methods of any type (no bean required), including `typeVars` so the client can
substitute generics.

### `GET /api/metadata?jdk=java.util`

Class names in a JDK package, read from `jrt:/`. Does not require the package in
`harken.packages`.

### `GET /api/metadata?type=Category` (no dot)

Looks up by **simple name**. If a Groovy default import would shadow it, the response carries
`shadowedBy`:

```json
{ "ok": true, "shadowedBy": "groovy.lang.Category", "methods": [] }
```

The client shows no methods in that case, only "add import". When several packages contain the
same simple name, `fqcn` is `null`, and it does not guess.

---

## Catalog and audit

### `GET /api/catalog`

Script list for `RESTRICTED` mode. In `FULL` mode it also returns, with no content (templates
only).

```json
{
  "ok": true,
  "mode": "RESTRICTED",
  "restricted": true,
  "maxRisk": "READ",
  "scripts": [
    { "name": "Find order", "risk": "READ", "desc": "One order by number", "path": "order-lookup.groovy" }
  ]
}
```

### `GET /api/audits?limit=50`

```json
{
  "ok": true,
  "queryable": true,
  "limit": 50,
  "records": [
    {
      "trace": "a1b2c3d4e5f6", "at": "2026-10-26T05:50:39Z",
      "operator": "admin", "ip": "127.0.0.1",
      "commit": false, "rolledBack": true, "ok": false,
      "errType": "HARKEN_ROW_LIMIT", "err": "3 affected rows exceeds the ceiling of 2",
      "ms": 10, "rows": 3,
      "digest": "sha256:4783a13796d6f71b",
      "script": "return orderService.markAllPaid()"
    }
  ]
}
```

Sensitive values in the script body are masked as `***`.

### `GET /api/health`

Runtime truth, not a config echo:

```json
{
  "ok": true,
  "version": "1.0.0",
  "environment": "prod",
  "mode": "RESTRICTED",
  "transaction": { "supported": true, "description": "SpringTransactionBridge(REQUIRES_NEW)" },
  "audit": { "stores": "jdbc + memory(200)", "queryable": true },
  "writeGuard": { "description": "JdbcWriteGuard(maxRows=50, denyDdl=true)" },
  "ai": { "enabled": true },
  "sessions": 1, "users": 2,
  "timeoutMs": 5000
}
```

This is the endpoint to check when asking "did my config actually take effect".

---

## AI channel

**When `harken.ai.token` is unset this endpoint does not exist** (404). Not "exists and refuses".

### `GET /ai/capabilities`

```bash
curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8080/harken/ai/capabilities
```

```json
{
  "ok": true,
  "ai": {
    "channel": "ai", "readOnly": true, "version": "1.0.0",
    "timeoutMs": 10000, "maxScriptLength": 102400,
    "resultMaxBytes": 8192, "outputMaxBytes": 32768
  }
}
```

### `POST /ai/query`

```bash
curl -s --noproxy '*' \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"script":"return orderService.count()"}' \
  http://127.0.0.1:8080/harken/ai/query
```

The request body has **no** `commit` field. Not because the server rejects it, but because the
type does not have one. Writes are rejected at the JDBC layer by `GuardedDataSource`.

### Optional signing

With `harken.ai.signing-secret` set, every request must carry three headers:

| Header | Content |
|---|---|
| `X-Harken-Timestamp` | Second-level epoch, within `signature-window-seconds` of the server |
| `X-Harken-Nonce` | 8–200 non-whitespace characters, **fresh for every request** |
| `X-Harken-Signature` | See below |

Canonical string (`\n` is a literal newline):

```
METHOD\nPATH\nTIMESTAMP\nNONCE\nHEX(SHA256(body))
```

`PATH` is the request URI including the base path, e.g. `/harken/ai/query`. The signature is
HMAC-SHA256 with `signing-secret`, lowercase hex.

```bash
BODY='{"script":"return orderService.count()"}'
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)
DIGEST=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $2}')
TO_SIGN=$(printf 'POST\n/harken/ai/query\n%s\n%s\n%s' "$TS" "$NONCE" "$DIGEST")
SIG=$(printf '%s' "$TO_SIGN" | openssl dgst -sha256 -hmac "$HARKEN_AI_SIGNING_SECRET" -hex | awk '{print $2}')

curl -s --noproxy '*' \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Harken-Timestamp: $TS" \
  -H "X-Harken-Nonce: $NONCE" \
  -H "X-Harken-Signature: $SIG" \
  -H 'Content-Type: application/json' \
  -d "$BODY" \
  http://127.0.0.1:8080/harken/ai/query
```

Server-side order is **window → signature → nonce registration**, and it cannot be reversed:
registering the nonce first hands the state machine to the attacker (a forged signature could
claim someone else's nonce and get a legitimate request judged as a replay).

---

## Status codes

| Code | When |
|---|---|
| 200 | Success |
| 302 | Unauthenticated `/harken/` (to login); `/harken` without trailing slash (to `/harken/`) |
| 400 | Invalid parameters |
| 401 | Not logged in / bad token / signature problem |
| 403 | Insufficient role, IP not allowlisted, cross-origin |
| 404 | Unknown endpoint (including an unconfigured AI channel) |
| 405 | Wrong method (usually GET against a POST endpoint) |
| 413 | Body too large |
| 429 | Locked out after too many login failures |
| 500 | Uncaught exception from the script (`errorType=HARKEN_INTERNAL`) |

Next: [Security model](security.md) · [Deployment](deploy.md)
