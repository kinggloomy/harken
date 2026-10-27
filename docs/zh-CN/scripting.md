# 脚本与补全

[English](../en/scripting.md) | **简体中文**

控制台里写的是 Groovy。本文讲清楚三件事：脚本里能看见什么、执行语义是什么、补全能推到什么程度
（以及推不出来时不做什么）。

---

## 1. 脚本里能看见什么

**Bean 名就是变量名。** 容器里叫 `orderService` 的 Bean，脚本里直接写 `orderService`。
解析是惰性的：用到哪个名字才去容器里取，没用到的不碰。

```groovy
return orderService.count()
```

**`println` 被捕获。** 输出会进响应体的 `output` 字段，不是应用自己的 stdout：

```groovy
println "开始"
def n = orderService.count()
println "共 $n 条"
return n
```

输出上限 `harken.output-max-bytes`（默认 32 KB），超出截断并在响应里标记。

**业务类可以直接 `import`。** 脚本的父 ClassLoader 就是应用的那个，所以：

```groovy
import com.example.order.OrderStatus

return orderService.listByStatus(OrderStatus.NEW).size()
```

脚本里没有隐含的 `harken` 变量，没有预设工具类，也没有 DSL，就是你的类加 Groovy。

---

## 2. 执行语义

| 项 | 行为 |
|---|---|
| 事务 | 包在 `REQUIRES_NEW` 里。`commit:false` → 执行完必回滚 |
| 无事务管理器 | 提交与试跑**都拒绝**（`HARKEN_NO_TRANSACTION`），不降级 |
| 超时 | `harken.timeout-ms`，到点中断线程（`ThreadInterrupt` AST 转换，循环里也能打断） |
| 编译缓存 | 按源码 SHA-256 命中，LRU 128 条。同一段脚本重复执行约 4 ms |
| 返回值 | 序列化成 JSON，上限 `harken.result-max-bytes`；不可序列化的降级成 `toString` |
| 审计 | 写在事务**之外**，回滚掉的那次也在 |

### 试跑与提交

```json
{ "script": "return orderService.markOne('SO-1001')", "commit": false }
```

| 字段 | 试跑 | 提交 |
|---|---|---|
| `commit` | `false` | `true` |
| 执行 | ✅ 真执行 | ✅ 真执行 |
| 落库 | ❌ 回滚 | ✅ 提交 |
| 所需角色 | `OPERATOR` | `ADMIN` |

试跑**不是**「只编译」也不是「模拟执行」。它真的跑完了，然后回滚。想只看语法：
`POST /api/compile`，不执行。

> 试跑之后请回读一次数据。`rolledBack=true` 只是引擎自己报的状态，回读一遍才算数。

---

## 3. 补全

补全的核心只有一句：**光标左边那个值是什么类型。**

### 六种触发

| 你敲的 | 补出什么 |
|---|---|
| `orderService.` | 实例方法，带真实参数名与返回类型 |
| `MoneyUtil.` | 静态方法（不要求它是 Bean） |
| `Hexagram.` | 枚举常量在最前，然后 `values()` / `valueOf()` |
| `com.example.MoneyUtil.` | 同上（全限定名直接写也行） |
| `orderService.topAmount(5).` | `List` 的方法 |
| `import java.util.` | 该包下的 JDK 类名 |

### 链式：沿返回类型走

`returns` 里带的是完整泛型，所以链能一段段推下去：

```groovy
orderService.topAmount(5).            // List<Map<String,Object>>
orderService.topAmount(5).get(0).     // Map<String,Object>
```

`E` / `K` / `V` 这类类型变量会用实参替换，方法签名显示成
`add(java.util.Map<java.lang.String, java.lang.Object>)` 而不是 `add(E)`。

### 闭包参数：从接收者元素类型推

```groovy
orderService.topAmount(5).forEach(m -> {
  m.                                  // m = Map → get / put / keySet / entrySet …
})

orderService.listOrders().each { it -> // Groovy 写法同样能推
  it.
}

Map<String,Integer> m = ...
m.each { k, v ->                       // Map 给两个参数：K 和 V
  v.
}
```

`eachWithIndex` 的第二个参数固定是 `Integer`，这个写死。

### 缺 import：第一条候选顺手补上

敲一个还没 import 的类简名：

```groovy
Category.        // 第一候选：「补 import com.example.Category + 补类名」
```

选中后**同时**插入 import 与类名，一次撤销即可全退（两个动作合并在一个 CodeMirror 操作里）。

插入位置接在最后一条 `import` 之后，这样最不容易破坏现有结构。

### 名称冲突：拒绝，不猜

如果这个简名已经被另一条显式 `import` 占了，候选里不出现「补 import」，只给提示。
理由是实际踩过：`Category` 可能是 `groovy.lang.Category`（Groovy 默认导入），也可能是
`com.example.Category`。插错了编译能过，行为却是错的，查起来比编译报错麻烦得多。

同理，多包同名类在写显式 import 之前，对简名敲点号**不弹方法**。

### 明确不做的

| 场景 | 为什么不做 |
|---|---|
| 链式调用跨行（`orderService` 换行再 `.topAmount()`） | 解析器只看当前行往前，跨行要把整段表达式重新括号配对，成本与收益不成比例 |
| 返回裸 `List`（无泛型实参） | 没有实参可替换，元素类型无从得知 |
| `import x.Y as Z` 别名 | 别名后的 Z 指向谁要解析别名表，这个功能没做，也不会假装能识别 |
| 推断 `def x = ...` 之后 `x.` | 那要靠执行才知道类型，不猜 |
| 重载方法选一个来推返回类型 | 重载有多个版本，选哪个都是猜 |

原则：**推不出来就不弹。** 弹出另一个类的方法比不弹更难查。

---

## 4. 常用写法

```groovy
// 查一条
return orderService.byNo("SO-1001")

// 批量看几个字段（返回值会 JSON 化）
return orderService.recent(10).collect { [no: it.no, status: it.status] }

// 改之前先看影响面
def before = orderService.statusOf("SO-1001")
orderService.markOne("SO-1001")
return [before: before, after: orderService.statusOf("SO-1001")]   // 试跑下两者相同

// 统计
return orderService.countByStatus().sort { -it.value }

// 分组计数（纯 Groovy，不绕 Stream）
return orderService.recent(100)
        .groupBy { it.status }
        .collectEntries { status, rows -> [status, rows.size()] }
```

写脚本时如果拿不准某个方法签名，敲点号看补全，那是从类上读出来的，不是文档里抄的。

---

## 5. 与审计的关系

每次执行都会写一条审计记录，字段包括：

| 字段 | 内容 |
|---|---|
| `trace` | 追踪号 |
| `operator` / `ip` | 谁、从哪来 |
| `commit` / `rolledBack` | 是否要求提交、是否真的回滚 |
| `ok` / `errType` / `err` | 成功与否、异常类型与消息 |
| `ms` / `rows` | 耗时、影响行数 |
| `digest` | 脚本正文的 SHA-256 前 16 位 |
| `script` | 脚本正文（敏感值已掩成 `***`） |

被护栏拒绝的执行 `ok=false`、`errType=HARKEN_ROW_LIMIT` 之类，同样入库。
想知道谁在动关键表，看的就是这些失败记录。

`GET /harken/api/audits?limit=50` 或页面上的「最近执行」面板。

下一步：[HTTP 接口](http-api.md) · [安全模型](security.md)
