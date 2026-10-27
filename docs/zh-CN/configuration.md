# 配置

[English](../en/configuration.md) | **简体中文**

所有配置项都在 `harken.*` 下。本文按你可能要改的顺序排，不是字母序。

---

## 最小可用配置

```yaml
harken:
  packages:
    - com.example
  web:
    users:
      - username: admin
        password: env:HARKEN_ADMIN_PASSWORD
```

只写这两段就能跑。其余都有默认值，而且默认值一律取更保守的那个。

---

## 顶层

| 配置 | 默认 | 说明 |
|---|---|---|
| `harken.enabled` | `true` | 总开关。关掉后什么都没注册 |
| `harken.packages` | `[]` | 扫描包（Bean 索引、类名索引）。**空 = 什么都扫不到** |
| `harken.timeout-ms` | `10000` | 单次执行的墙钟上限，超时中断线程 |
| `harken.max-script-length` | `102400` | 脚本字符数上限（100 KB） |
| `harken.result-max-bytes` | `8192` | 返回值序列化上限，超出截断并标记 |
| `harken.output-max-bytes` | `32768` | `println` 输出上限 |
| `harken.require-transaction-for-ai` | `true` | AI 通道必须能包事务；无事务时直接拒绝 |
| `harken.print-banner` | `true` | 启动时打印状态三行 |

> `harken.packages` 配错（比如写成公司域名的旧前缀）不会报错，只是命中 0 个 Bean，
> 表现出来是脚本里 Bean 全是 null。启动横幅会打印命中数量，可以对着看一眼。

---

## `harken.web.*`（控制台）

| 配置 | 默认 | 说明 |
|---|---|---|
| `harken.web.enabled` | `true` | 关掉即不注册任何控制台端点 |
| `harken.web.base-path` | `/harken` | 控制台与 API 的前缀 |
| `harken.web.allowed-ips` | `127.0.0.1, ::1, ::ffff:127.0.0.1` | 精确 IP / IPv4 CIDR / `*`。**空 = 全拒绝** |
| `harken.web.session-idle-minutes` | `30` | 空闲超时 |
| `harken.web.session-max-hours` | `8` | 会话绝对上限，到点必须重新登录 |
| `harken.web.max-login-failures` | `5` | 连续失败几次锁定 |
| `harken.web.lock-minutes` | `15` | 锁定时长 |
| `harken.web.generate-password-if-no-users` | `true` | 没配用户时生成临时账号并打到日志 |
| `harken.web.max-body-bytes` | 见代码 | 请求体上限 |
| `harken.web.trust-forwarded-for` | `false` | 是否信任 `X-Forwarded-For`。**只有放在自己完全控制的 nginx 后面才开** |
| `harken.web.environment` | `""` | 状态条显示的环境名，同时是提交确认词 |
| `harken.web.mode` | `FULL` | `FULL` = 自由编辑；`RESTRICTED` = 只能跑目录里的脚本，页面无编辑器 |

### 两种模式的实际差别

| | `FULL` | `RESTRICTED` |
|---|---|---|
| 页面编辑器 | 有 | 无，只有脚本列表 |
| 可执行内容 | 任意 Groovy | 只限脚本目录里已存在的脚本 |
| 提交确认 | 需要输入环境确认词 | 同左 |
| 适用 | 本地 / 预发 | 生产 |

`RESTRICTED` 的脚本目录由 `harken.catalog.*` 指定，且每个脚本带危险等级，
超过 `harken.catalog.max-risk` 的不会出现在列表里。

### 密码

三种写法，`harken.web.users[].password`：

```yaml
password: admin123                          # 明文，只适合本地
password: env:HARKEN_ADMIN_PASSWORD         # 环境变量，推荐
password: 'pbkdf2-sha256$120000$...$...'    # 哈希串
```

生成哈希串（`harken-web` 的 jar 在本地仓库里）：

```bash
java -cp ~/.m2/repository/com/harlanking/harken-web/1.0.0/harken-web-1.0.0.jar \
  com.harlanking.harken.web.auth.PasswordHash '你的密码'
```

用 `env:` 写法的好处是**变量缺失时启动失败**，而不是静默退化成空密码。

### 角色

`harken.web.users[].role` 有两个值：

| 角色 | 能做 |
|---|---|
| `OPERATOR` | 试跑（回滚）、查看审计 |
| `ADMIN` | 以上 + 提交（真落库）+ 重载配置 |

---

## `harken.ai.*`（AI 通道）

| 配置 | 默认 | 说明 |
|---|---|---|
| `harken.ai.enabled` | `true` | 下面的 token 才会决定端点是否存在 |
| `harken.ai.token` | `""` | **空 = 端点不注册**，不存在注册了再拒绝的情况 |
| `harken.ai.signing-secret` | `""` | 非空 = **强制**所有 AI 请求带 HMAC 签名 |
| `harken.ai.signature-window-seconds` | `60` | 签名时间窗，超出即拒 |

```yaml
harken:
  ai:
    token: env:HARKEN_AI_TOKEN
    signing-secret: env:HARKEN_AI_SIGNING_SECRET
```

这里**没有** `ai.read-only` 这类开关。AI 通道的只读来自 `ReadOnlyRequest` 类型本身没有
`commit` 字段。能关掉的只读不算只读。

签名方式见 [HTTP 接口](http-api.md#ai-通道)。

---

## `harken.guard.*`（写入护栏）

| 配置 | 默认 | 说明 |
|---|---|---|
| `harken.guard.enabled` | `true` | 关掉等于完全不碰应用的 JDBC |
| `harken.guard.max-affected-rows` | `0` | 单次执行影响行数上限。**`0` = 不限** |
| `harken.guard.deny-tables` | `[]` | 表黑名单，支持 `secret_*` / `*_log` / `*` |
| `harken.guard.deny-ddl` | `false` | 是否拒绝 DDL |

推荐的生产值：

```yaml
harken:
  guard:
    max-affected-rows: 100        # 0 太宽松：一条 UPDATE 忘了 WHERE 就全表
    deny-tables:
      - "*_ledger"
      - "settlement_*"
    deny-ddl: true
```

护栏在**执行途中**拦，不是事先检查 SQL 文本：`GuardedDataSource` 包一层动态代理，
每条 `executeUpdate` 累加计数，越过上限立刻抛 `HARKEN_ROW_LIMIT`。所以
`markAllPaid()` 要改 5 行、上限设成 2，会在改到第 3 行时被拦下，而不是整次调用被跳过。

被拦下的执行**也会进审计**。想知道谁在动关键表，看的往往就是这些失败记录。

---

## `harken.catalog.*`（预置脚本目录）

| 配置 | 默认 | 说明 |
|---|---|---|
| `harken.catalog.provider` | `none` | `classpath` / `file` / `none` |
| `harken.catalog.file` | `""` | `file` 模式下的目录路径 |
| `harken.catalog.allow-hot-reload` | `true` | 文件改动后是否重读 |
| `harken.catalog.max-risk` | `READ` | 允许出现的最高危险等级 |

```yaml
harken:
  catalog:
    provider: file
    file: /etc/yourapp/harken-scripts
    max-risk: WRITE          # READ / WRITE / DDL
```

脚本头部用注释声明等级与说明，解析器只认这一种格式：

```groovy
// @name 查订单
// @risk READ
// @desc 按订单号查一条，不改任何东西
return orderService.byNo("SO-1001")
```

---

## `harken.audit.*`（审计存储）

| 配置 | 默认 | 说明 |
|---|---|---|
| `harken.audit.memory.enabled` | `true` | 进程内环形缓冲，最近 200 条 |
| `harken.audit.memory.capacity` | `200` | 条数 |
| `harken.audit.jdbc.enabled` | `false` | 落库审计 |
| `harken.audit.jdbc.table` | `harken_audit` | 表名，可带 schema 前缀 |
| `harken.audit.jdbc.auto-create` | `true` | 自动建表；生产通常由 DBA 建好后关掉 |

两者同时启用时是 `CompositeAuditStore`：内存那份给页面快速翻页，JDBC 那份给事后追溯。
**审计写在事务之外**。回滚掉的那次最需要留痕，所以不能放在同一个事务里。

---

## 环境变量覆盖

Spring Boot 的标准规则照常生效，优先级从高到低：

```
命令行 --harken.web.mode=RESTRICTED
环境变量 HARKEN_WEB_MODE=RESTRICTED
application-prod.yml
application.yml
```

排查配置没生效时，先看 `/harken/api/health` 返回的实际状态，它读的是运行期的值，
不是把配置文件回显给你。

---

## 完整示例（生产向）

```yaml
harken:
  packages:
    - com.example.order
    - com.example.billing
  timeout-ms: 5000
  result-max-bytes: 4096

  web:
    allowed-ips:
      - 10.20.0.0/24          # 跳板机网段，不是 *
    trust-forwarded-for: true # 前面只有我们自己的一层 nginx
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
      auto-create: false      # DBA 建表
```

这段配置里没有 `ai` 段。生产上不给 AI 开通道，端点就不存在，也少一个要守的地方。
