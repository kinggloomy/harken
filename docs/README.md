# Harken documentation / 文档索引

English · [简体中文](README.zh.md)

| # | Topic | English | 简体中文 |
|---|---|---|---|
| 1 | Quick start: dependency, config, first script | [en](en/quickstart.md) | [zh-CN](zh-CN/quickstart.md) |
| 2 | Configuration: every key, defaults, production example | [en](en/configuration.md) | [zh-CN](zh-CN/configuration.md) |
| 3 | Scripting & completion: what a script sees, what inference refuses | [en](en/scripting.md) | [zh-CN](zh-CN/scripting.md) |
| 4 | HTTP API: endpoints, error types, signing | [en](en/http-api.md) | [zh-CN](zh-CN/http-api.md) |
| 5 | Security model: blocked, not blocked, and why | [en](en/security.md) | [zh-CN](zh-CN/security.md) |
| 6 | Deployment: three setups, nginx, containers | [en](en/deploy.md) | [zh-CN](zh-CN/deploy.md) |
| 7 | Performance: measured numbers, and what was not measured | [en](en/performance.md) | [zh-CN](zh-CN/performance.md) |

## Design notes / 设计记录

| Doc | Language |
|---|---|
| [Completion design](zh-CN/design-completion.md): how the type inference works, and the measurements behind each decision | 中文 only |

## Published elsewhere

- Javadoc: <https://harken.harlanking.com/api/1.0.0/>
- Maven Central: <https://central.sonatype.com/artifact/com.harlanking/harken-spring-boot-starter>

## Suggested reading order

- **Evaluating it?** Quick start → Security model. The second one tells you whether it fits your
  environment, faster than the first tells you whether it works.
- **Deploying it?** Configuration → Deployment → Security model §5.
- **Contributing?** [CONTRIBUTING](../CONTRIBUTING.md) → the design note → the code.
