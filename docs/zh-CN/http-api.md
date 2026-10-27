# HTTP 接口

[English](../en/http-api.md) | **简体中文**

所有端点都挂在 `harken.web.base-path` 下（默认 `/harken`）。请求与响应都是 JSON/UTF-8。

---

## 约定

**同源校验。** 除 GET 与登录外，所有写请求都要带 `Origin`，且必须与 `Host` 一致。不一致返回
`HARKEN_CROSS_ORIGIN`。这条防不住所有 CSRF，但能挡住另一个页面偷偷发 POST 这类情况。

**错误形状。** 失败一律是这个结构，HTTP 状态码同时表达语义：

```json
{
  "ok": false,
  "errorType": "HARKEN_ROW_LIMIT",
  "error": "影响行数 3 超过上限 2，已在第 3 行中止",
  "traceId": "a1b2c3d4e5f6"
}
```

**`errorType` 取值。** 前端按它分支，不按 `error` 文本。

| 值 | 含义 |
|---|---|
| `COMPILATION` | Groovy 编译失败（另有 `line` / `column`） |
| `HARKEN_ROW_LIMIT` | 越过影响行数上限 |
| `HARKEN_TABLE_FORBIDDEN` | 命中表黑名单 |
| `HARKEN_DDL_FORBIDDEN` | 拒绝 DDL |
| `HARKEN_NO_TRANSACTION` | 没有事务管理器，提交与试跑都拒绝 |
| `HARKEN_TIMEOUT` | 超过 `harken.timeout-ms` |
| `HARKEN_SCRIPT_TOO_LONG` | 超过 `max-script-length` |
| `HARKEN_CROSS_ORIGIN` | Origin 缺失或不匹配 |
| `HARKEN_FORBIDDEN` | 角色不足（`OPERATOR` 尝试提交） |
| `HARKEN_AI_UNAUTHORIZED` | AI Token 缺失或无效 |
| `HARKEN_SIGNATURE_*` | `MISSING` / `INVALID` / `STALE` / `REPLAY` |

---

## 认证

```bash
curl -s --noproxy '*' -c /tmp/hk.jar \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"***"}' \
  http://127.0.0.1:8080/harken/auth/login
```

响应：

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

会话靠 `Set-Cookie`（`HttpOnly`、`SameSite=Strict`）。之后的请求带 `-b /tmp/hk.jar` 即可。

| 端点 | 说明 |
|---|---|
| `POST /auth/logout` | 退出 |
| `GET /auth/me` | 当前身份、角色、剩余时间 |
| `POST /auth/reload` | 重载用户与脚本目录。**密钥轮换后旧签名立即失效** |

连续失败 `max-login-failures` 次后锁定 `lock-minutes` 分钟。用户不存在时也会跑一次 PBKDF2，
响应耗时和密码错误时一致，否则从响应时间差里能看出哪些用户名存在。

---

## 执行

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

| 字段 | 说明 |
|---|---|
| `ok` | 执行是否成功（护栏拦截时为 `false`） |
| `result` | 脚本返回值，JSON 化后的结果 |
| `output` | `println` 捕获的内容 |
| `error` / `errorType` | 失败时的消息与分类 |
| `durationMs` | 端到端耗时 |
| `rolledBack` | **是否真的回滚了** |
| `affectedRows` | 累计影响行数 |
| `truncated` | 返回值或输出是否被截断 |
| `hint` | 可选的下一步建议 |
| `channel` | `human` / `ai` |
| `traceId` | 与审计记录对应 |

`commit:true` 需要 `ADMIN`；`FULL` 模式下还要带确认词（与 `harken.web.environment` 一致）。

### `POST /api/compile`

只编译，不执行，不碰数据库。

```json
{ "ok": false, "errorType": "COMPILATION", "error": "unexpected token: }", "line": 3, "column": 12 }
```

---

## 元数据（补全用）

### `GET /api/metadata`

一次拿全：Bean 名、包 → 类名、类简名 → 全名。**不含方法**（几百个类的方法表太大，
方法按需拉）。约 24 KB / 800 个类。

### `GET /api/metadata?bean=orderService`

Bean 的实例方法：

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

类的静态方法 + 枚举常量 + 类型变量。三种都走这一个入口。

### `GET /api/metadata?inst=java.util.List`

任意类型（不要求是 Bean）的**实例**方法，`typeVars` 一并返回，供前端替换泛型。

### `GET /api/metadata?jdk=java.util`

JDK 包下的类名。走 `jrt:/` 读，不需要 `harken.packages` 里有它。

### `GET /api/metadata?type=Category`（不带点号）

按**简名**查。如果这个名字会被 Groovy 默认导入抢走，响应里多一个 `shadowedBy`：

```json
{ "ok": true, "shadowedBy": "groovy.lang.Category", "methods": [] }
```

前端看到 `shadowedBy` 就不弹方法，只提示补 import。多个包有同名类时 `fqcn` 返回 `null`，
不猜。

---

## 目录与审计

### `GET /api/catalog`

`RESTRICTED` 模式下的脚本列表。`FULL` 模式下也返回，但没有内容（只作模板）。

```json
{
  "ok": true,
  "mode": "RESTRICTED",
  "restricted": true,
  "maxRisk": "READ",
  "scripts": [
    { "name": "查订单", "risk": "READ", "desc": "按订单号查一条", "path": "order-lookup.groovy" }
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
      "errType": "HARKEN_ROW_LIMIT", "err": "影响行数 3 超过上限 2",
      "ms": 10, "rows": 3,
      "digest": "sha256:4783a13796d6f71b",
      "script": "return orderService.markAllPaid()"
    }
  ]
}
```

脚本正文里的敏感值已掩成 `***`。

### `GET /api/health`

运行期实际状态，不是配置回显：

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

排查配置有没有生效就看这个。

---

## AI 通道

**没配 `harken.ai.token` 时这个端点根本不存在**（404），不是注册了再拒绝。

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

请求体里**没有** `commit` 字段。不是服务端会拒绝它，是这个类型里就没有这个字段。
写操作会在数据库层被 `GuardedDataSource` 用只读连接拒掉。

### 可选签名

配了 `harken.ai.signing-secret` 后，每个请求必须带三个头：

| 头 | 内容 |
|---|---|
| `X-Harken-Timestamp` | 秒级 epoch，与服务器相差不得超过 `signature-window-seconds` |
| `X-Harken-Nonce` | 8–200 个非空白字符，**每次都要换新的** |
| `X-Harken-Signature` | 见下 |

待签串（`\n` 是字面换行）：

```
METHOD\nPATH\nTIMESTAMP\nNONCE\nHEX(SHA256(body))
```

`PATH` 是 request URI，含 base-path，例如 `/harken/ai/query`。签名是 HMAC-SHA256，
密钥即 `signing-secret`，输出小写十六进制。

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

校验顺序必须是**时间窗 → 签名 → nonce 登记**。如果先登记 nonce，伪造签名的人也能抢先
占用别人的 nonce，合法请求反而会被判成重放。

---

## 状态码

| 码 | 场景 |
|---|---|
| 200 | 成功 |
| 302 | 未登录访问 `/harken/`（跳登录页）；`/harken` 无尾斜杠时跳 `/harken/` |
| 400 | 参数不合法 |
| 401 | 未登录 / Token 无效 / 签名问题 |
| 403 | 角色不足、IP 不在白名单、跨源 |
| 404 | 端点不存在（含未配置的 AI 通道） |
| 405 | 方法不对（多数是 GET 打了 POST 端点） |
| 413 | 请求体超限 |
| 429 | 登录失败过多被锁 |
| 500 | 脚本抛出未捕获异常（`errorType=HARKEN_INTERNAL`） |

下一步：[安全模型](security.md) · [部署](deploy.md)
