# 快速开始

[English](../en/quickstart.md) | **简体中文**

从零到「脚本能调用你自己的 Bean」，大约五分钟。前提是你的应用是 Spring Boot 3.x。

---

## 0. 前置条件

不符合就先别往下走，会白折腾：

| 项 | 要求 | 怎么查 |
|---|---|---|
| JDK | 17 / 21 / 25 | `java -version` |
| Spring Boot | 3.x | `spring-boot-starter-parent` 的版本 |
| Servlet 容器 | 6.0（Jakarta） | Boot 3 默认内嵌 Tomcat 10.1，满足 |
| 事务管理器 | 有（推荐） | 有 `spring-boot-starter-jdbc` / `-jpa` 即可 |

没有事务管理器时应用照样能启动，但**提交和试跑都会被拒绝**（`HARKEN_NO_TRANSACTION`）。
因为这两种情况都不安全：一种是改了撤不回来，另一种是写了库却报告说已回滚。

---

## 1. 加依赖

```xml
<dependency>
    <groupId>com.harlanking</groupId>
    <artifactId>harken-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle：

```groovy
implementation 'com.harlanking:harken-spring-boot-starter:1.0.0'
```

---

## 2. 最小配置

三行就够：

```yaml
harken:
  packages:
    - com.example          # 你的业务包：Bean 与类名索引都从这里扫
  web:
    users:
      - username: admin
        password: env:HARKEN_ADMIN_PASSWORD
```

密码支持三种写法：

| 写法 | 例 | 说明 |
|---|---|---|
| 明文 | `admin123` | 只适合本地 |
| 环境变量引用 | `env:HARKEN_ADMIN_PASSWORD` | 推荐；变量缺失时**启动失败**，不会退化成空密码 |
| PBKDF2 哈希 | `pbkdf2$120000$<salt>$<hash>` | 生成方式见 [配置](configuration.md#密码) |

> 不配 `users` 时会生成一个临时账号并打印在启动日志里。这只适合本地试用，
> 不要这样上任何共享环境。

---

## 3. 启动与访问

```bash
mvn spring-boot:run
# 或
java -jar target/your-app.jar --server.port=8081
```

打开 <http://127.0.0.1:8081/harken/>，用刚才的账号登录。

启动日志里会打印三行状态：

```
HARKEN  mode=FULL  restricted=false  ai=disabled
HARKEN  transaction=SpringTransactionBridge  audit=jdbc  guard=enabled
HARKEN  users=1  allowedIps=[127.0.0.1, ::1]
```

---

## 4. 端点一览（别猜路径）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/harken/` | 控制台页面 |
| POST | `/harken/auth/login` | 登录，body `{"username","password"}`（**不是** `/api/login`） |
| POST | `/harken/auth/logout` | 退出 |
| GET | `/harken/auth/me` | 当前身份与剩余会话时间 |
| POST | `/harken/auth/reload` | 重载用户/目录配置（密钥轮换后旧签名立即失效） |
| POST | `/harken/api/execute` | 执行脚本（`commit:false` = 试跑） |
| POST | `/harken/api/compile` | 只编译不执行（看语法错） |
| GET | `/harken/api/catalog` | 预置脚本目录（`RESTRICTED` 模式的数据源） |
| GET | `/harken/api/metadata` | 补全用的类名索引（不含方法） |
| GET | `/harken/api/metadata?bean=orderService` | 该 Bean 的实例方法签名 |
| GET | `/harken/api/metadata?type=com.example.MoneyUtil` | 该类的静态方法与枚举常量 |
| GET | `/harken/api/metadata?inst=java.util.List` | JDK 类型的方法（走 `jrt:/`，不用配 `packages`） |
| GET | `/harken/api/metadata?jdk=java.util` | JDK 包下的类名 |
| GET | `/harken/api/audits` | 最近执行记录 |
| GET | `/harken/api/health` | 事务 / 审计 / 守卫 / AI 的真实状态 |
| POST | `/harken/ai/query` | AI 通道（**只在配了 `harken.ai.token` 时存在**） |
| GET | `/harken/ai/capabilities` | AI 通道的能力清单 |

完整字段与示例见 [HTTP 接口](http-api.md)。

---

## 5. 第一段脚本

试跑（`Ctrl+Enter`）：执行，然后回滚。

```groovy
return orderService.markOne("SO-1001")
```

状态行：`rows=1 rolledBack=true`。**然后一定要回读一次**，否则「回滚」只是返回值上的一句话：

```groovy
return orderService.statusOf("SO-1001")     // 应该仍是 NEW，不是 PAID
```

提交（`Ctrl+Shift+Enter`）：`FULL` 模式下会要求你输入环境确认词（`harken.web.environment` 的值），
然后真的落库。

---

## 6. 补全怎么用

六种触发都试一下，就知道它能做到什么程度：

| 你敲的 | 补出什么 | 靠什么推的 |
|---|---|---|
| `orderService.` | 实例方法（带真实参数名与返回类型） | Bean 反射 |
| `MoneyUtil.` | 静态方法 | 类反射；不要求它是 Bean |
| `Hexagram.` | 枚举常量在最前，然后是 `values()` / `valueOf()` | 类反射 |
| `orderService.topAmount(5).` | `List` 的方法，泛型已替换成具体类型 | 沿链取每段返回类型 |
| `list.forEach(m -> { m.` | `Map` / 元素类型的方法 | 从接收者元素类型推闭包参数 |
| `Category.`（没 import） | 第一候选是「补 import + 补类名」 | 索引 + 默认导入遮蔽检测 |

推不出类型时不弹，比如链式调用跨行，或者返回的 `List` 没带泛型实参。
细节见 [脚本与补全](scripting.md)。

---

## 7. 五分钟验收清单

```bash
# ① 页面能开（未登录会 302 到登录页）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/harken/

# ② 登录（本地有代理时必须带 --noproxy）
curl -s --noproxy '*' -c /tmp/hk.jar -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的密码"}' \
  http://127.0.0.1:8081/harken/auth/login

# ③ 自检：事务 / 审计 / 守卫实际是什么状态
curl -s --noproxy '*' -b /tmp/hk.jar http://127.0.0.1:8081/harken/api/health

# ④ 试跑一段（必须带 Origin，否则会被同源校验拦下）
curl -s --noproxy '*' -b /tmp/hk.jar -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:8081' \
  -d '{"script":"return orderService.count()","commit":false}' \
  http://127.0.0.1:8081/harken/api/execute
```

四条都对，就算接好了。

---

## 8. 报错对照表（都是实际踩过的）

| 现象 | 原因 | 怎么办 |
|---|---|---|
| 登录 404，日志说「未知的控制台端点：/login」 | 登录路径是 `/auth/login` | 见上面端点表 |
| 登录失败，日志里有随机密码 | 没配 `users` | 配 `users`，或从日志抄临时密码 |
| `HARKEN_CROSS_ORIGIN` | 直接 curl 没带 `Origin`，或 `Origin` 与 `Host` 不一致 | 带上正确的 `Origin` 头 |
| AI 端点 404 | 没配 `harken.ai.token`，端点**根本没注册** | 配上 token |
| 脚本里 Bean 是 `null` | 类不在 `harken.packages` 白名单里 | 加包名 |
| 试跑却真的落库了 | 应用没有事务管理器 | 加 `spring-boot-starter-jdbc`，或自己声明 `PlatformTransactionManager` |
| 补全只有类名没有方法 | 敲点号时方法表还没拉回来 | 稍等再敲一次点号；或看 `/api/metadata?bean=` 是否返回 |
| 页面白屏，`assets/app.js` 404 | 访问 `/harken`（无尾斜杠）时相对路径解析错 | 用 `/harken/` |

---

## 9. 三条安全提醒（本地也一样）

1. **不要在生产库上开 `FULL` + `ADMIN`。** 加依赖三十秒就有改库能力，这个便利同时就是风险。
2. **`allowed-ips` 只放回环。** 需要远程访问就走 SSH 隧道：`ssh -L 8081:127.0.0.1:8081 user@host`，
   而不是把白名单改成 `*`。
3. **AI 通道的只读靠事务回滚和连接只读**，不是数据库账号只读。生产上给应用配一个只有 `SELECT`
   权限的账号，是最省事的兜底。

下一步：[配置项](configuration.md) · [安全模型](security.md)
