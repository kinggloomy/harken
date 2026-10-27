# Security model

**English** | [简体中文](../zh-CN/security.md)

This document has one writing rule: **list what is blocked and what is not.** A page that only
says "we are secure" is worthless. A page that says where the model breaks is a page people dare
to use.

---

## 0. One sentence

**Harken is not a sandbox.** It narrows "connect to the database and run whatever" into "run a
Groovy snippet inside a controlled transaction, with rollback, audit, and a read-only AI channel".
But the script runs **inside your application's JVM**, with exactly your application's authority.

The test is simple:

> Would you let this person `ssh` into the application server and run code as your app?
> If not, do not give them Harken commit rights.

---

## 1. What is blocked (with evidence)

Eighteen cases. A/B/C/D are verified by `scripts/pen-test.sh` against live instances (16/16
passing); the three F cases cover audit integrity and are asserted by unit tests. They are
awkward to construct from a shell script, but they are assertions, not claims.

| # | Attack surface | What the test does | Result |
|---|---|---|---|
| A1 | Replay | Re-send one valid request verbatim | 200, then **401 `HARKEN_SIGNATURE_REPLAY`** |
| A2 | Tampering | Change the body, keep the old signature | **401 `HARKEN_SIGNATURE_INVALID`** |
| A3 | Stale request | Use a one-hour-old request (signature itself valid) | **401 `HARKEN_SIGNATURE_STALE`** |
| A4 | Missing signature | Only a Bearer token | **401 `HARKEN_SIGNATURE_REQUIRED`** |
| B1 | Channel confusion | AI token against the human write endpoint | **401** (an AI token is not a session) |
| B2 | Channel confusion | Human session against the AI endpoint | **401 `HARKEN_AI_UNAUTHORIZED`** |
| B3 | Privilege escalation | `commit:true` on the AI channel | **400**, explicitly rejected, not silently ignored |
| B4 | Privilege escalation | `OPERATOR` account with `commit=true` | **403 `HARKEN_FORBIDDEN_COMMIT`** |
| B5 | Anonymous access | `/api/*` without a session | **401** |
| C1 | IP bypass | Direct connection from outside the allowlist | **403** |
| C2 | IP bypass | Forged `X-Forwarded-For: <allowlisted address>` | **403** (header untrusted by default) |
| C3 | CSRF | Malicious `Origin` | **403 `HARKEN_CROSS_ORIGIN`** |
| D1 | Guard escape | DDL (`create table`) | **Rejected `HARKEN_DDL_FORBIDDEN`** |
| D2 | Guard escape | Touch a denylisted table | **Rejected `HARKEN_TABLE_FORBIDDEN`** |
| D3 | Guard escape | Bulk update with no `where` | **Rejected `HARKEN_ROW_LIMIT`** and rolled back |
| F1 | Audit loss | Business transaction rolls back | Audit uses its own connection and commits; **the record survives** |
| F2 | Secret in audit | Script contains `password="123456"` | Stored as `password="***"` |
| F3 | Secret in logs | Stack trace contains `jdbc:mysql://root:pw@host` | Masked to `root:***@host` |

Reproduce:

```bash
mvn -q -DskipTests package
bash scripts/pen-test.sh          # starts three instances, cleans up afterwards
```

---

## 2. What is not blocked (know this before going live)

| # | Gap | Why it is this way | What you can do |
|---|---|---|---|
| **G1** | **Dangerous APIs are not intercepted.** `new File("/etc/passwd").text`, `Runtime.exec(...)`, `System.exit(1)` all really execute | Hard-blocking collides immediately with the legitimate "I need to read a file to compute a checksum" case, and Groovy reflection can bypass any allowlist, so a blocklist would only create false confidence | Give **commit rights** only to people you trust; constrain the process at the container/OS level (read-only root filesystem, non-root, seccomp) |
| **G2** | **Dynamically built table names are invisible**: `"update " + table + " set ..."` | `SqlInspector` is a lightweight lexer, not a data-flow analysis (JSqlParser would add 400 KB and CVEs) | The real backstop is "read-only connection + mandatory rollback", not a table denylist |
| **G3** | **With `trust-forwarded-for=true`, that header is the identity** | Without trusting it in a proxy setup, every source becomes the proxy IP and the allowlist is decorative. With it, nginx must **overwrite** the header | Only enable it when exactly one proxy you control sits in front, and use `proxy_set_header X-Forwarded-For $remote_addr;` (never `$proxy_add_x_forwarded_for`, which appends) |
| **G4** | **Masking is regex-based, not DLP** | Only "key name + assignment" shapes are recognized; `def p = "s3cret"; connect(user, p)` is missed | Do not hard-code credentials in scripts; treat the audit table itself as a sensitive system |
| **G5** | **Return values are not masked** | The return value is business data; masking it would be falsifying it (an AI reading `token=***` will decide wrongly) | Audit stores only a digest of the result (`sha256:... len=...`), never the body |
| **G6** | **AI read-only relies on rollback + a read-only connection, not a read-only DB account** | That is what lets a dry run observe real affected-row counts | Give the application a `SELECT`-only database account in production. That is the cheapest hard boundary available |
| **G7** | **Execution timeout only interrupts the thread**; it cannot reclaim allocated heap | The JVM has no safe way to kill a thread | Cap `maxScriptLength` and result size; treat timeouts as incidents, not routine |
| **G8** | **Same-origin checks only look at `Origin`**; requests without it are allowed (curl/SDKs need this) | Double-edged: sufficient in browsers, and non-browser callers already hold a session token | Do not embed the console in third-party iframes (already covered by `X-Frame-Options: DENY` + `frame-ancestors 'none'`) |

---

## 3. Why these guarantees are not "configuration"

This is the core difference from a generic script-execution platform. Four structural decisions:

| Guarantee | Carrier | Why not a switch |
|---|---|---|
| AI can only read | `ReadOnlyRequest` has no `commit` field | A read-only flag you can flip is a fake read-only flag; the first new requirement flips it |
| No transaction ⇒ refuse everything | Constructor-time check + `HARKEN_NO_TRANSACTION` | "Reported as rolled back but actually persisted" is far more dangerous than an error |
| Audit outside the transaction | Separate connection, separate commit | The rolled-back run is the one you most need on record |
| `harken-core` has no Spring | Maven Enforcer rule `forbid-spring-in-core` | "Works without Spring" must be a structural fact, not a promise |

The Enforcer rule runs at `validate`, so sneaking a `spring-context` dependency into core turns
the build red immediately.

---

## 4. Request signing

With `harken.ai.signing-secret` set, every `/harken/ai/*` request needs three headers:

```
X-Harken-Timestamp: 1700000000            # second-level epoch
X-Harken-Nonce: 8c1f...                   # 8-200 non-whitespace chars, single use
X-Harken-Signature: 3f9a...               # lowercase hex HMAC-SHA256
```

Canonical string (byte-for-byte, `\n` is a newline):

```
METHOD \n PATH \n TIMESTAMP \n NONCE \n HEX(SHA256(body))
```

- **`PATH` is the request URI**, i.e. `/harken/ai/query` including the base path.
- **GET requests** sign the SHA-256 of an empty body.
- Verification order: **window → signature → nonce registration**. Reversing it either lets
  stale requests fill the nonce table or lets forged signatures claim other people's nonces.
- Window defaults to 60 seconds and applies in both directions (future timestamps are rejected).

Compute it yourself, without any Harken code:

```bash
SECRET='your-secret'; TS=$(date +%s); NONCE=$(openssl rand -hex 16)
BODY='{"script":"orderService.count()"}'
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $NF}')
CANON=$(printf 'POST\n/harken/ai/query\n%s\n%s\n%s' "$TS" "$NONCE" "$BODY_HASH")
SIG=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')
```

**Why not "secret configured but signing optional"?** Because that produces the illusion of
protection. Either leave it unset (token only, stated plainly) or set it and enforce it.

---

## 5. Deployment baseline

Do these and you get the level of security this document describes:

1. **Bind to loopback**: `server.address=127.0.0.1`, or `-p 127.0.0.1:8080:8080` in containers.
2. **When it must be exposed**: put nginx in front, narrow `allowed-ips` to the office subnet,
   turn signing on.
3. **Application account**: give it `SELECT` only; add a whitelisted table separately if writes
   are genuinely needed.
4. **No credentials in Git**: `password: env:HARKEN_ADMIN_PASSWORD`,
   `signing-secret: env:HARKEN_SIGNING_SECRET`.
5. **Console stays internal**: it is an interface that can modify a database, not a product page.
6. **Run non-root**: `USER 10001` in containers, read-only root filesystem (this is what covers G1).

---

## 6. Reporting

If you find a bypass, include: version, `/harken/api/health` output, a reproduction script, and
"what you expected vs what happened". **Do not open a public issue**. See
[SECURITY.md](../../SECURITY.md) at the repository root.
