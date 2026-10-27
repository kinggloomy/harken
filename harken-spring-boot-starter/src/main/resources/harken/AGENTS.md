# Harken — 给 AI Agent 的使用说明

你正在面对一个**只读**的运行时查询接口。它运行在目标 Java 应用进程内，
能直接调用该应用里注册的 Spring Bean，因此可以拿到代码里拿不到的真实数据
（当前生效的配置、内存里的缓存、数据库里刚写入的行）。

它不是数据库客户端：你写 Groovy，它执行，返回结果。

---

## 1. 你能做什么

- 用 Groovy 调用应用内的 Bean 方法，把返回值带回来
- 用 `println` 输出中间结果（与返回值分开返回）
- 查询任意 Bean 的公开方法（先看能力清单）

## 2. 你不能做什么（不是"不建议"，是做不到）

| 限制 | 表现 |
|---|---|
| 写数据库 | 事务被置为只读，数据库层直接拒绝；或执行后强制回滚 |
| 传 `commit` 参数 | 请求直接 400，本通道没有"提交"这个概念 |
| 进程操作 | `Runtime` / `ProcessBuilder` / `System.exit` 被校验器拦截 |
| 编译期执行注解 | `@Grab` / `@GrabConfig` 等被拦截 |
| 长时间运行 | 超时（默认 10 秒）后中断 |
| 超大返回 | 结果超过字节上限会被截断，响应里 `truncated=true` 并带 `hint` |

## 3. 怎么调用

```bash
# 能力清单：先看有哪些 Bean、哪些方法（gzip，几百 KB，建议缓存）
curl -s --compressed \
  -H "Authorization: Bearer $HARKEN_AI_TOKEN" \
  "$HARKEN_URL/ai/capabilities"

# 查一次
curl -s -X POST "$HARKEN_URL/ai/query" \
  -H "Authorization: Bearer $HARKEN_AI_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"script":"orderService.count()"}'
```

或者直接用随包的 `harken-query.sh`（见第 7 节）。

## 4. 响应格式

```json
{
  "ok": true,
  "result": "5",
  "output": "",
  "error": null,
  "errorType": null,
  "durationMs": 2,
  "rolledBack": true,
  "truncated": false,
  "hint": null,
  "channel": "ai"
}
```

| 字段 | 含义 |
|---|---|
| `ok` | 脚本是否跑成功。**注意：`ok=true` 不代表改了数据**，见 `rolledBack` |
| `result` | 脚本 `return` 的值的文本形式。没 `return` 就是 `null` |
| `output` | 脚本里 `println` 的内容 |
| `errorType` | 失败时机器可读的分类，见下表 |
| `truncated` + `hint` | 成对出现。**看到 `truncated=true` 就不要基于结果下结论** |
| `rolledBack` | 恒为 `true`（本通道永远回滚） |

## 5. 失败时怎么自我修正

| `errorType` | 含义 | 你该怎么做 |
|---|---|---|
| `COMPILATION` | 语法错误，`error` 里带行列号 | 修语法；Groovy 里字符串用单引号或双引号都可以 |
| `MissingMethodException` | 方法名或参数类型不对 | 先查 `/ai/capabilities` 确认签名 |
| `HARKEN_FORBIDDEN_API` | 用了禁用的 API | 换一种写法；不要试图绕过 |
| `HARKEN_AI_READONLY_UNSAFE` | 环境没有事务保护，只读保证不成立 | 停止查询，告知使用者需要配置事务管理器 |
| `TIMEOUT` | 超过执行时限 | 缩小查询范围（加 `limit` / `where`），不要重试同样的脚本 |
| `HARKEN_AI_UNAUTHORIZED` | Token 缺失或错误 | 检查 `HARKEN_AI_TOKEN`，不要重试 |

## 6. 好的查询习惯

1. **先看能力清单，再写脚本。** 猜 Bean 名和方法签名是在浪费轮次。
2. **结果可能很大，先取数量再取明细。**
   `orderService.count()` 通过之后再 `orderService.topAmount(5)`，
   而不是直接 `findAll()`。
3. **`toString()` 是你的朋友也是最危险的东西。** 返回值最终走 `String.valueOf`，
   返回一个带懒加载代理的 JPA 实体可能直接抛异常。优先返回标量或 `Map`。
4. **Groovy 里 `def` 很宽松，但字段拼错不会报错。** `orderService.cuont()` 会抛
   `MissingMethodException`，而 `orderService.count`（没有括号）会返回一个方法引用
   并被序列化成奇怪的东西。**永远带括号**。
5. **一次只问一件事。** 一个脚本里塞十个查询，失败时你不知道是哪一个。

## 7. 随包的调用脚本

starter jar 里有 `harken/harken-query.sh`：

```bash
# 从构建产物里取出来
jar -xf harken-spring-boot-starter-<version>.jar harken/harken-query.sh
chmod +x harken-query.sh

export HARKEN_URL=http://127.0.0.1:8080/harken
export HARKEN_AI_TOKEN=***

./harken-query.sh 'orderService.count()'
./harken-query.sh --capabilities | head -50
```

## 8. 审计

每一次调用都会写一条审计记录，`channel=ai`、`operator=ai`。
脚本正文、耗时、是否回滚、结果摘要都在里面。
**这意味着你的查询是留痕的**。不要查与任务无关的数据。

---

Harken — runtime console for Java applications. Licensed under Apache License 2.0.
