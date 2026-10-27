# Harken

[English](README.md) | **简体中文**

[![build](https://github.com/kinggloomy/harken/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kinggloomy/harken/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.harlanking/harken-spring-boot-starter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.harlanking/harken-spring-boot-starter)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](#环境要求)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](#环境要求)

Harken 是给运行中的 Java 应用用的一个网页控制台。打开页面写一段 Groovy，可以直接调用你应用里的 Spring Bean，回车就能看到返回值、影响行数和执行日志。默认不写库：脚本跑在一个独立事务里，执行完就回滚，只有你明确点「提交」才会保存。

它主要解决这类现场问题：线上某个订单还卡在 `NEW`，想看看是哪一步没走通。平常的做法是登服务器跑个临时 `main`、临时加个接口重新发版，或者把数据导出来贴到聊天里问人。Harken 让你在应用已有的页面里把这些事一次做完，登录、IP 白名单、事务管理器都沿用你已经配好的那套。

## 主要功能

- **不用重新发版**：对正在运行的 Bean 跑 Groovy，编辑器补全认你的 classpath
- **默认试跑**：执行包在 `REQUIRES_NEW` 事务里，不提交就回滚
- **执行留痕**：谁、什么时候、跑了什么、有没有落库、失败原因，被拒绝的也记
- **AI 通道只能读**：单独的端点，入参类型里没有 `commit` 字段
- **写入有上限**：影响行数上限、敏感表黑名单、DDL 拦截，执行过程中检查，不是先把脚本扫一遍
- **两种接入方式**：Spring Boot starter，或者纯 `main()` + `HarkenRuntime.builder()`。`harken-core` 的 classpath 上没有 Spring

## 不适合用来做什么

- **不是沙箱**：脚本和你应用同权限，同 JVM、同 classloader、同数据库账号。如果你不会给某个人服务器的 ssh 权限，就不要给他 Harken 的提交权限。详见[安全模型](docs/zh-CN/security.md)
- **不做运维**：不管发布、不管配置、没有监控大盘
- **不是 MCP Server，不是多租户，也不是脚本 IDE**。这些功能不做，不在计划里
- **`FULL` 模式不建议直连生产库**：预发随便用。生产建议 `RESTRICTED` 模式（只跑审过的脚本目录），或者干脆只用只读通道

## 快速开始

**1. 加依赖**

```xml
<dependency>
    <groupId>com.harlanking</groupId>
    <artifactId>harken-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**2. 配置 Bean 扫描包和登录账号**

```yaml
harken:
  packages:
    - com.example.order
    - com.example.billing
  web:
    users:
      - username: admin
        password: env:HARKEN_ADMIN_PASSWORD
```

**3. 启动应用，打开 `http://127.0.0.1:8080/harken/`**

```groovy
// 试跑：真的执行，结束后回滚
return orderService.markOne("SO-1001")
```

执行完状态栏会显示 `rows=1 rolledBack=true`。再查一次这条数据，它还是 `NEW`，说明回滚真的发生了，不是响应里写了句话。

完整流程见[快速开始](docs/zh-CN/quickstart.md)。

## 控制台

控制台就是这个 Servlet 容器里的一个页面，不额外开端口，不起独立进程，运行期不需要 Node 构建。

- **补全**：敲 `orderService.` 出来的是从 Bean 上现读的方法签名；敲 `Hexagram.` 枚举常量排在最前面；`list.forEach(m -> { m.` 会从接收者的元素类型推出 `m` 的类型，泛型也一起替换掉（给的是 `Map` 的方法，不是 `E` 的）
- **自动 import**：名字还没 import 时，候选第一条会同时插入 import 并补上类名，一次撤销就能退回去。如果这个简名已经被另一个 import 占了，它不猜，直接拒绝插入
- **深色 / 浅色主题**：按浏览器记住，首屏之前生效，不会闪一下白底

## AI 通道

`POST /harken/ai/query` 的入参类型是 `ReadOnlyRequest`，这个类型里没有能改成写操作的字段，也没有配置项能放宽它，所以以后重构也不会不小心改坏。没配 `harken.ai.token` 时这个端点不会注册，不是注册了再拒绝，而是根本没有这段逻辑。

需要防重放可以开 HMAC 签名（`harken.ai.signing-secret`）。换密钥调 `POST /harken/auth/reload`，旧签名立刻失效。

## 环境要求

| | |
|---|---|
| Java | 17 / 21 / 25（三个版本在 Linux 和 Windows 上都跑过） |
| Spring Boot | 3.x（可选，纯 Java 方式完全不需要 Spring） |
| Servlet | 6.0（Jakarta），由你的容器提供 |
| 数据库 | 任意 `DataSource`，JDBC 护栏走标准 JDBC |
| 浏览器 | 任何现代浏览器。控制台自带 CodeMirror 5，不连 CDN |

## 模块

| 构件 | 作用 | 不依赖 Spring |
|---|---|---|
| `harken-core` | 编译、执行、绑定、审计，整个引擎 | ✅ |
| `harken-jdbc` | 行数闸、表黑名单、事务化审计存储 | ✅ |
| `harken-web` | Servlet 层：控制台 API + 独立的 AI API | ✅ |
| `harken-spring` | Bean 解析、事务桥、classpath 索引 | — |
| `harken-spring-boot-starter` | 自动配置，一个依赖一行配置 | — |

仓库里还有两个可以直接跑的示例：`harken-demo` 是 Spring Boot + H2，`harken-demo-library` 是纯 Java + 内嵌 Tomcat。这两个不发布到 Maven Central，是示例，不是库。

## 文档

| | 简体中文 | English |
|---|---|---|
| 快速开始 | [docs/zh-CN/quickstart.md](docs/zh-CN/quickstart.md) | [docs/en/quickstart.md](docs/en/quickstart.md) |
| 配置 | [docs/zh-CN/configuration.md](docs/zh-CN/configuration.md) | [docs/en/configuration.md](docs/en/configuration.md) |
| 脚本与补全 | [docs/zh-CN/scripting.md](docs/zh-CN/scripting.md) | [docs/en/scripting.md](docs/en/scripting.md) |
| HTTP 接口 | [docs/zh-CN/http-api.md](docs/zh-CN/http-api.md) | [docs/en/http-api.md](docs/en/http-api.md) |
| 安全模型 | [docs/zh-CN/security.md](docs/zh-CN/security.md) | [docs/en/security.md](docs/en/security.md) |
| 部署 | [docs/zh-CN/deploy.md](docs/zh-CN/deploy.md) | [docs/en/deploy.md](docs/en/deploy.md) |
| 性能 | [docs/zh-CN/performance.md](docs/zh-CN/performance.md) | [docs/en/performance.md](docs/en/performance.md) |

API 参考（Javadoc）：<https://harken.harlanking.com/api/1.0.0/>

## 从源码构建

```bash
git clone https://github.com/kinggloomy/harken.git
cd harken
mvn clean install            # 构建 7 个模块并跑测试
java -jar harken-demo/target/harken-demo-*.jar
# 控制台：http://127.0.0.1:8080/harken/
```

需要 JDK 17+ 和 Maven 3.6+。JDK 版本不对时构建会直接失败并说明原因，不会给一堆看不懂的编译错误。

## 参与贡献

欢迎提 issue 和 PR。动手前先看一下 [CONTRIBUTING.md](CONTRIBUTING.md)，里面有几条架构约束，踩了会被打回，最重要的一条是 `harken-core` 永远不能引入 Spring 依赖。

安全漏洞请按 [SECURITY.md](SECURITY.md) 的方式反馈，不要开公开 issue。

## 许可证

[Apache License 2.0](LICENSE)
