# 安全模型

[English](../en/security.md) | **简体中文**

本文把拦住的和没拦住的都列出来。只说安全的文档没有参考价值，写清楚哪些场景防不住才有人敢用。

---

## 0. 一句话定位

**Harken 不是沙箱。** 它把「改数据库」从「随便连库」收敛成「在受控事务里执行一段 Groovy，
可回滚、可审计、AI 只能读」。但脚本本身跑在**你应用的 JVM 里**，拥有和你的应用完全相同的权限。

判断标准很简单：

> 你会不会让这个人直接 `ssh` 到应用服务器、用应用的身份跑一段代码？
> 如果不会，就别给他 Harken 的提交权限。

---

## 1. 已拦住的（附实测证据）

共 18 条。其中 A/B/C/D 四类由 `scripts/pen-test.sh` 真起实例验证（16/16 通过），
F 类三条属于审计完整性，由单元测试覆盖。这几条在渗透脚本里不好构造，但同样有断言。

| # | 攻击面 | 具体做法 | 结果 |
|---|---|---|---|
| A1 | 重放 | 抓到一次合法请求，原样重发 | 第一次 200，第二次 **401 `HARKEN_SIGNATURE_REPLAY`** |
| A2 | 篡改 | 改 body 但沿用旧签名 | **401 `HARKEN_SIGNATURE_INVALID`** |
| A3 | 过期请求 | 用一小时前的请求（签名本身有效） | **401 `HARKEN_SIGNATURE_STALE`** |
| A4 | 漏签名 | 只带 Bearer Token | **401 `HARKEN_SIGNATURE_REQUIRED`** |
| B1 | 通道串用 | 拿 AI Token 调人类写接口 | **401**（AI Token 不是会话） |
| B2 | 通道串用 | 拿人类会话调 AI 接口 | **401 `HARKEN_AI_UNAUTHORIZED`** |
| B3 | 越权提交 | 在 AI 通道传 `commit:true` | **400**，明确拒绝而非静默忽略 |
| B4 | 越权提交 | `OPERATOR` 账号 `commit=true` | **403 `HARKEN_FORBIDDEN_COMMIT`** |
| B5 | 匿名访问 | 不带会话访问 `/api/*` | **401** |
| C1 | IP 绕过 | 从非白名单地址直连 | **403** |
| C2 | IP 绕过 | 伪造 `X-Forwarded-For: <白名单地址>` | **403**（默认不信任该头） |
| C3 | CSRF | 恶意 `Origin` 提交 | **403 `HARKEN_CROSS_ORIGIN`** |
| D1 | 守卫逃逸 | DDL（`create table`） | **拒绝 `HARKEN_DDL_FORBIDDEN`** |
| D2 | 守卫逃逸 | 触碰黑名单表 | **拒绝 `HARKEN_TABLE_FORBIDDEN`** |
| D3 | 守卫逃逸 | 无 `where` 的批量更新 | **拒绝 `HARKEN_ROW_LIMIT`** 并回滚 |
| F1 | 审计丢失 | 业务回滚 | 审计走独立连接 + 独立事务提交，**记录仍在** |
| F2 | 泄密入审计 | 脚本里写 `password="123456"` | 审计中为 `password="***"` |
| F3 | 泄密入日志 | 异常栈里带 `jdbc:mysql://root:pw@host` | 掩成 `root:***@host` |

自己复现：

```bash
mvn -q -DskipTests package
bash scripts/pen-test.sh          # 自己起三个实例、跑完自动清理
```

---

## 2. 没拦住的（已知缺口，必须知情后再上线）

| # | 缺口 | 为什么这样选 | 你能做什么 |
|---|---|---|---|
| **G1** | **危险 API 不拦截**：`new File("/etc/passwd").text`、`Runtime.exec(...)`、`System.exit(1)` 都会真的执行 | 硬拦会立刻撞上「我的脚本就是要读个文件算校验和」的合法需求，而且 Groovy 反射能绕过任何白名单，做成拦截只会给人虚假的安全感 | 只把**提交权限**给可信的人；用容器/系统级手段限制应用的系统访问（只读根文件系统、非 root 运行、seccomp） |
| **G2** | **动态拼接的表名识别不到**：`"update " + table + " set ..."` | `SqlInspector` 是轻量词法分析，不做数据流追踪（引入 JSqlParser 会带来 400 KB 与 CVE 负担） | 兜底靠的是连接级只读加必回滚，不是表名黑名单 |
| **G3** | **`trust-forwarded-for=true` 之后，该头就是身份** | 反代场景下不信任它，所有来源都变成反代 IP，白名单形同虚设；信任它，就必须由 nginx **覆盖写入** | 只在「前面一定有一层自己控制的代理」时打开，并在 nginx 用 `proxy_set_header X-Forwarded-For $remote_addr;`（不要用 `$proxy_add_x_forwarded_for` 追加） |
| **G4** | **脱敏是正则，不是 DLP** | 只认「键名 + 赋值」形态；`def p = "s3cret"; connect(user, p)` 认不出 | 不要把凭据硬编码进脚本；审计库本身按敏感系统对待 |
| **G5** | **返回值不脱敏** | 返回值是业务数据本身，掩掉等于篡改数据（AI 读到 `token=***` 会做错判断） | 审计只存返回值摘要（`sha256:... len=...`），不存正文 |
| **G6** | **AI 通道的只读靠事务回滚和连接只读**，不是数据库账号只读 | 这样试跑才能真的看到影响行数 | 生产上给应用配一个**只有 SELECT 权限**的账号，是最省事的兜底 |
| **G7** | **脚本执行超时只能中断线程**，不能回收已分配的堆内存 | JVM 没有安全的「杀线程」手段 | 限制 `maxScriptLength` 与结果大小；把超时当事故看，而不是常态 |
| **G8** | **同源校验只看 `Origin`**，没有 `Origin` 的请求放行（curl / SDK 需要） | 双刃：浏览器场景足够，非浏览器场景靠会话令牌本身 | 控制台不要嵌进第三方 iframe（已用 `X-Frame-Options: DENY` + `frame-ancestors 'none'` 兜住） |

---

## 3. 这些保证为什么不是配置开关

这是 Harken 和其他脚本执行平台的主要差别，来自四个结构上的决定：

| 保证 | 载体 | 为什么不做成开关 |
|---|---|---|
| AI 只能读 | `ReadOnlyRequest` 类型里没有 `commit` 字段 | 能关掉的只读等于假只读，需求一变开关就会被打开 |
| 无事务时一律拒绝 | 构造期检查 + `HARKEN_NO_TRANSACTION` | 写了库却报告说已回滚，比直接报错危险得多 |
| 审计在事务之外 | 独立连接、独立提交 | 回滚掉的那次最需要留痕 |
| `harken-core` 无 Spring | Maven Enforcer 规则 `forbid-spring-in-core` | 能不能脱离 Spring 必须是结构上的事实，不能只写在文档里 |

Enforcer 规则写在父 POM 里，`mvn validate` 阶段就失败。想偷偷加一个 `spring-context` 依赖，
构建直接报错。

---

## 4. 请求签名

配了 `harken.ai.signing-secret` 后，所有 `/harken/ai/*` 请求必须带三个头：

```
X-Harken-Timestamp: 1700000000            # 秒级 epoch
X-Harken-Nonce: 8c1f...                   # 8-200 个非空白字符，一次性
X-Harken-Signature: 3f9a...               # 小写十六进制 HMAC-SHA256
```

待签串（逐字节一致，换行是 `\n`）：

```
METHOD \n PATH \n TIMESTAMP \n NONCE \n HEX(SHA256(body))
```

- **PATH 用 `requestURI`**，即 `/harken/ai/query` 这一段（含 base-path）。
- **GET 的 body 摘要**是空串的 SHA-256。
- 校验顺序：**时间窗 → 签名 → nonce 登记**，不能换。先查时间窗，可以挡住拿过期请求刷满
  nonce 表；先验签名，可以挡住伪造签名抢占别人的 nonce，导致合法请求被判成重放。
- 时间窗默认 60 秒，两侧都算（未来时间也拒）。

自己算一遍，不依赖 Harken 的任何代码：

```bash
SECRET='your-secret'; TS=$(date +%s); NONCE=$(openssl rand -hex 16)
BODY='{"script":"orderService.count()"}'
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $NF}')
CANON=$(printf 'POST\n/harken/ai/query\n%s\n%s\n%s' "$TS" "$NONCE" "$BODY_HASH")
SIG=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')
```

**为什么不支持配了密钥但签名可选**：那样只会造成「我以为开了」的错觉。要么不配，只靠 Token
并在文档里写明；要么配了就强制。

---

## 5. 部署基线

照做就能达到本文档描述的安全水平：

1. **绑定回环**：`server.address=127.0.0.1`，或容器 `-p 127.0.0.1:8080:8080`。
2. **必须对外时**：放在 nginx 后面 + `allowed-ips` 收窄到办公网段 + 打开签名。
3. **应用账号**：数据库账号只给 `SELECT`；确实需要写，再单独给一张白名单表。
4. **凭据不进 Git**：`password: env:HARKEN_ADMIN_PASSWORD`、`signing-secret: env:HARKEN_SIGNING_SECRET`。
5. **控制台只给内网**：它是能改数据库的界面，不是给用户用的产品页。
6. **非 root 运行**：容器里 `USER 10001`，根文件系统只读（这一条挡的是 G1）。

---

## 6. 报告问题

发现绕过方式，请带上：版本号、`/harken/api/health` 输出、复现脚本，以及你期望发生什么、
实际发生了什么。**不要开公开 issue**，流程见仓库根目录的 [SECURITY.md](../../SECURITY.md)。
