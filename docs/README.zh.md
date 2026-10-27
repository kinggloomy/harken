# Harken 文档索引 / Documentation index

[English](README.md) · 简体中文

| # | 主题 | 简体中文 | English |
|---|---|---|---|
| 1 | 快速开始：依赖、配置、第一段脚本 | [zh-CN](zh-CN/quickstart.md) | [en](en/quickstart.md) |
| 2 | 配置：全部配置项、默认值、生产示例 | [zh-CN](zh-CN/configuration.md) | [en](en/configuration.md) |
| 3 | 脚本与补全：脚本能看见什么、推不出类型时怎么办 | [zh-CN](zh-CN/scripting.md) | [en](en/scripting.md) |
| 4 | HTTP 接口：端点、错误类型、签名 | [zh-CN](zh-CN/http-api.md) | [en](en/http-api.md) |
| 5 | 安全模型：拦住了什么、没拦住什么、为什么 | [zh-CN](zh-CN/security.md) | [en](en/security.md) |
| 6 | 部署：三种形态、nginx、容器 | [zh-CN](zh-CN/deploy.md) | [en](en/deploy.md) |
| 7 | 性能：实测数字，以及没有测的部分 | [zh-CN](zh-CN/performance.md) | [en](en/performance.md) |

## 设计记录

| 文档 | 语言 |
|---|---|
| [代码提示方案](zh-CN/design-completion.md)：类型推导怎么做的，每条决定背后的实测 | 仅中文 |

## 其他位置

- Javadoc：<https://harken.harlanking.com/api/1.0.0/>
- Maven Central：<https://central.sonatype.com/artifact/com.harlanking/harken-spring-boot-starter>

## 建议的阅读顺序

- **在评估要不要用？** 先看快速开始，再看安全模型。安全模型会更快回答能不能用在你这个环境里。
- **准备部署？** 配置 → 部署 → 安全模型第 5 节。
- **想参与开发？** [CONTRIBUTING](../CONTRIBUTING.md) → 设计记录 → 代码。
