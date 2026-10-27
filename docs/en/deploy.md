# Deployment

**English** | [简体中文](../zh-CN/deploy.md)

Three typical setups, ordered by increasing exposure. **Default to the first one** unless you can
articulate why you need more.

---

## Scenario 1: only on your own machine (simplest, safest)

```yaml
harken:
  packages: [com.example]
  web:
    users:
      - username: admin
        password: env:HARKEN_ADMIN_PASSWORD
```

```bash
export HARKEN_ADMIN_PASSWORD="$(openssl rand -base64 12)"
mvn spring-boot:run
# browser: http://127.0.0.1:8080/harken/
```

No `allowed-ips`, no TLS, no signing, because **only loopback can reach it**.

If even that feels too open, add `server.address=127.0.0.1` (Spring Boot's default already).

---

## Scenario 2: shared on an internal network

Keep the application on loopback and let nginx forward. The process itself never faces the
network, which is a habit worth keeping.

```properties
server.address=127.0.0.1
harken.web.allowed-ips=10.0.0.0/8
# nginx rewrites the source header, so it must be trusted, otherwise every request looks like 127.0.0.1
harken.web.trust-forwarded-for=true
harken.web.environment=prod
harken.ai.token=env:HARKEN_AI_TOKEN
harken.ai.signing-secret=env:HARKEN_SIGNING_SECRET
```

nginx site:

```nginx
location /harken/ {
    allow 10.0.0.0/8;
    deny  all;

    proxy_pass http://127.0.0.1:8080;

    # Must OVERWRITE with $remote_addr. Never use $proxy_add_x_forwarded_for here:
    # it appends, leaving the client's forged value leftmost, so anyone can claim to be internal.
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Real-IP       $remote_addr;
    proxy_set_header Host            $host;

    # The console keeps connections open and returns large payloads; buffering hurts
    proxy_buffering off;
    proxy_read_timeout 120s;
}
```

**Two allowlists is deliberate**: the nginx layer stops external scanning, the application layer
stops "nginx was misconfigured". Both must fail for traffic to leak.

Once `trust-forwarded-for` is on, **that header is the identity**, so the
`proxy_set_header X-Forwarded-For $remote_addr;` line is a security control, not boilerplate.

### Production recommendation: `RESTRICTED` + catalog

```yaml
harken:
  web:
    mode: RESTRICTED
  catalog:
    provider: file
    file: /etc/yourapp/harken-scripts
    max-risk: READ
  guard:
    max-affected-rows: 50
    deny-tables: ["*_ledger"]
    deny-ddl: true
```

In `RESTRICTED` mode the page has no editor; only scripts in the catalog directory can run. Make
that directory readable by the app user only:

```bash
install -d -m 0750 -o yourapp -g yourapp /etc/yourapp/harken-scripts
```

---

## Scenario 3: containers / Kubernetes

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar app.jar
# Non-root: covers "script reads files inside the container / changes the system"
RUN useradd -r -u 10001 harken
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

```bash
docker run --rm \
  -p 127.0.0.1:8080:8080 \
  --read-only --tmpfs /tmp \
  --env-file harken.env \
  yourapp:latest
```

Notes:

- Bind to loopback explicitly with `-p 127.0.0.1:8080:8080`. **Never** `-p 8080:8080`, which
  publishes on every interface.
- `--read-only` + `--tmpfs /tmp`: no writable filesystem, so scripts cannot read business config.
- Credentials come from `--env-file` or a Kubernetes Secret, never a build arg
  (`docker history` shows build args).

In Kubernetes, **`getRemoteAddr()` inside the container is the gateway IP**. You then need
`harken.web.trust-forwarded-for=true`, and the Ingress must **overwrite** that header (same rule
as scenario 2).

Secret volumes are usually mounted `0644`; Harken logs a warning at startup. That is a hint, not a
refusal to start. Failing startup over file permissions causes more outages than the leak it
tries to prevent.

---

## Configuration reference

| Key | Default | Notes |
|---|---|---|
| `harken.web.base-path` | `/harken` | Console and API prefix |
| `harken.web.allowed-ips` | loopback trio | Exact IP / IPv4 CIDR / `*`. **Empty = deny all** |
| `harken.web.users[].password` | — | Plain / `env:VAR` / PBKDF2 hash |
| `harken.web.session-idle-minutes` | `30` | Idle timeout |
| `harken.web.session-max-hours` | `8` | Absolute cap |
| `harken.web.max-login-failures` | `5` | Failures before lockout |
| `harken.web.environment` | empty | Environment label, also the commit confirmation word |
| `harken.web.mode` | `FULL` | `RESTRICTED` = catalog scripts only |
| `harken.web.trust-forwarded-for` | `false` | Only behind your own proxy |
| `harken.ai.token` | empty | Empty = AI endpoint not registered |
| `harken.ai.signing-secret` | empty | Non-empty = signing required |
| `harken.catalog.provider` | `none` | `classpath` / `file` / `none` |
| `harken.catalog.max-risk` | `READ` | Highest risk level visible in restricted mode |
| `harken.guard.max-affected-rows` | `0` | Row ceiling, `0` = unlimited |
| `harken.guard.deny-tables` | empty | Denylist, supports `*_order` / `*` |
| `harken.guard.deny-ddl` | `false` | Reject DDL |
| `harken.audit.jdbc.enabled` | `false` | Persist audit records |

Full detail: [Configuration](configuration.md).

---

## 30-second post-deploy check

```bash
# 1. Endpoint alive, mode as expected
curl -s --noproxy '*' http://127.0.0.1:8080/harken/api/health | head -c 400

# 2. Not reachable from outside (run on another host: expect timeout or 403)
curl -m 5 -s -o /dev/null -w '%{http_code}\n' http://<internal-ip>:8080/harken/

# 3. Startup log shows the three status lines
#    mode / restricted / ai channel / ai signature
```

Then one more: **try a commit with an `OPERATOR` account** and expect 403. Role separation is
the last line of defence, so confirm it by hand once.

---

## Rollback

Harken does not alter your data, create tables (unless `audit.jdbc.auto-create` is on) or write to
a registry. So rollback is:

1. Remove the dependency or set `harken.enabled=false`, restart.
2. If JDBC audit is enabled, `drop table harken_audit` (or keep it: it is a standalone table).

No state migration, no background process to shut down.
