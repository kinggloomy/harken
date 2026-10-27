# 部署

[English](../en/deploy.md) | **简体中文**

三种部署方式，按暴露面从小到大排。默认选场景一，除非你能说清楚为什么需要更大。

---

## 场景一：只在自己机器上用（最省事，也最安全）

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
# 浏览器：http://127.0.0.1:8080/harken/
```

不需要 `allowed-ips`、TLS 和签名，因为只有回环地址能连上。

如果连本机都不想暴露，加 `server.address=127.0.0.1`（Spring Boot 默认已经是）。

---

## 场景二：团队内网共用

应用仍然绑回环，由 nginx 转发。应用进程不直接对外，这个习惯要守住。

```properties
server.address=127.0.0.1
harken.web.allowed-ips=10.0.0.0/8
# 关键：nginx 会改写来源头，必须信任它，否则所有请求的来源都是 127.0.0.1
harken.web.trust-forwarded-for=true
harken.web.environment=prod
harken.ai.token=env:HARKEN_AI_TOKEN
harken.ai.signing-secret=env:HARKEN_SIGNING_SECRET
```

nginx 站点：

```nginx
location /harken/ {
    # 只允许办公网段
    allow 10.0.0.0/8;
    deny  all;

    proxy_pass http://127.0.0.1:8080;

    # 必须用 $remote_addr **覆盖**，不要用 $proxy_add_x_forwarded_for（那是追加，
    # 客户端自带的假值会留在最左边，等于让任何人自称内网地址）
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Real-IP       $remote_addr;
    proxy_set_header Host            $host;

    # 控制台是长连接 + 大响应（能力清单几百 KB），关掉缓冲更顺
    proxy_buffering off;
    proxy_read_timeout 120s;
}
```

**两层白名单各挡一类问题**：nginx 那层挡外部扫描，应用那层挡 nginx 自己配错的情况。两层同时出错才会漏。

打开 `trust-forwarded-for` 之后，**来源头就是身份**，所以那行
`proxy_set_header X-Forwarded-For $remote_addr;` 属于安全配置，不是可选项。

### 生产建议：`RESTRICTED` + 脚本目录

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

`RESTRICTED` 下页面没有编辑器，只能跑 `/etc/yourapp/harken-scripts` 里审过的脚本。
目录只读挂给应用用户即可：

```bash
install -d -m 0750 -o yourapp -g yourapp /etc/yourapp/harken-scripts
```

---

## 场景三：容器 / K8s

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar app.jar
# 非 root：挡住脚本读容器内文件、改系统这类越界操作
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

要点：

- `-p 127.0.0.1:8080:8080`：**不要**写 `-p 8080:8080`，那会绑到所有网卡。
- `--read-only` + `--tmpfs /tmp`：容器内没有可写文件系统，脚本读不到业务配置。
- 凭据走 `--env-file` 或 K8s Secret，不写进镜像（`docker history` 能看到 build args）。

K8s 里 **容器内的 `getRemoteAddr()` 是网关 IP**。此时必须
`harken.web.trust-forwarded-for=true`，并确保 Ingress **覆盖**该头（同场景二）。

Secret 挂载出来的文件权限常是 `0644`，启动时会打一条 WARN。只是提示，不会拒绝启动：
因为权限是 644 就不让应用起来，造成的发布事故比泄漏本身更常见。

---

## 配置项速查

| 配置 | 默认 | 说明 |
|---|---|---|
| `harken.web.base-path` | `/harken` | 控制台与 API 前缀 |
| `harken.web.allowed-ips` | 回环三个 | 精确 IP / IPv4 CIDR / `*`；**空 = 全拒绝** |
| `harken.web.users[].password` | — | 明文 / `env:VAR` / PBKDF2 哈希串 |
| `harken.web.session-idle-minutes` | `30` | 空闲超时 |
| `harken.web.session-max-hours` | `8` | 会话绝对上限 |
| `harken.web.max-login-failures` | `5` | 连续失败几次锁定 |
| `harken.web.environment` | 空 | 状态条环境名，也是提交确认词 |
| `harken.web.mode` | `FULL` | `RESTRICTED` = 只能跑目录里的脚本 |
| `harken.web.trust-forwarded-for` | `false` | 只在自己控的代理后面开 |
| `harken.ai.token` | 空 | 空 = AI 端点不注册 |
| `harken.ai.signing-secret` | 空 | 非空 = 强制签名 |
| `harken.catalog.provider` | `none` | `classpath` / `file` / `none` |
| `harken.catalog.max-risk` | `READ` | 受限模式下允许的最高危险等级 |
| `harken.guard.max-affected-rows` | `0` | 影响行数上限，`0` = 不限 |
| `harken.guard.deny-tables` | 空 | 黑名单，支持 `*_order` / `*` |
| `harken.guard.deny-ddl` | `false` | 是否拒绝 DDL |
| `harken.audit.jdbc.enabled` | `false` | 审计落库 |

完整说明见 [配置](configuration.md)。

---

## 上线后 30 秒自检

```bash
# 1. 端点活着，且模式符合预期
curl -s --noproxy '*' http://127.0.0.1:8080/harken/api/health | head -c 400

# 2. 对外确实不通（在另一台机器上执行，应该超时或被 403）
curl -m 5 -s -o /dev/null -w '%{http_code}\n' http://<内网IP>:8080/harken/

# 3. 启动日志里有三行关键状态
#    mode / restricted / ai channel / ai signature
```

前两条都对，再加一条：**用 `OPERATOR` 账号试一次提交**，应该 403。角色隔离是最后一道防线，
建议亲手确认一次。

---

## 回滚

Harken 不改你的业务数据，不建表（除非开了 `audit.jdbc.auto-create`），不写注册中心。
所以回滚只需要两步：

1. 去掉依赖或设 `harken.enabled=false`，重启。
2. 如果开了数据库审计，`drop table harken_audit`（或留着，它只是一张独立表）。

没有状态需要迁移，也没有后台进程需要收尾。
