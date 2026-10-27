# Harken 代码提示方案（实测定版）

> **状态：已落地。** §1–§3 是实测结论，实施后没变；§4 起已按真实实现改写过，
> 与最初计划的差异集中在「两个端点合成一个 `/metadata` 四形态」和「静态方法不做类型白名单」，
> 原因见 §4.3 / §6.3。
>
> 本文所有数字来自真实运行，环境：Windows 11 / JDK 17 / Spring Boot 3.3.5，
> fat jar（`LaunchedClassLoader`，20+ 三方 jar）与 exploded（`AppClassLoader`）两种形态各跑一遍。
> 未测：JDK 21/25 的补全行为、Linux、IDE 热部署。这三项不影响结构结论。
>
> 说明：本文是设计推导记录，目前只有中文版。面向使用者的说明见
> [脚本与补全](scripting.md)（[English](../en/scripting.md)）。

---

## 1. 目标

1. Spring 管理的 Bean（实例方法）
2. 非 Spring 管理的类（静态方法）
3. `import` 语句本身就能补全类名，对应 IDE 里「边写 import 边提示类」的体验
4. 覆盖 JDK 类（`import java.util.Arr` → `ArrayList`）
5. 最低资源消耗，且不牺牲安全性

---

## 2. 实测数据

### 2.1 类枚举（`PathMatchingResourcePatternResolver`）

| pattern | fat jar | exploded | 说明 |
|---|---|---|---|
| `classpath*:{pkg}/*.class` | 3 类 / **141–411 μs** | 3 类 / 581–1341 μs | 包级直接子项 |
| `classpath*:{pkg}/**/*.class` | 9 类 / **327–593 μs** | 9 类 / 1.1–2.1 ms | 递归 |
| `classpath*:{pkg}/*/*.class` | 2 类 / 163–258 μs | 2 类 / 1.2–1.4 ms | `*` 不跨 `/`（ant 语义确认） |
| `classpath*:{pkg}/**/{Prefix}*.class` | 4 类 / **134–740 μs** | 4 类 / 775–1572 μs | 前缀匹配可用 |
| `classpath*:{pkg}/*/`（目录模式） | **2 个目录 / exists=true** | **0 个** ❗ | **不可靠，见 §3.2** |
| `classpath*:java/util/*.class` | **0 类** ❗ | **0 类** ❗ | **JDK 拿不到，见 §3.1** |
| `classpath*:org/springframework/core/**/*.class` | 470 类 / 3.3–5.6 ms | 470 类 / 3.5–6.0 ms | 千级压力 |
| `classpath*:org/springframework/**/*.class` | 7014 类 / **29–50 ms** | 6889 类 / 32–48 ms | 宽范围代价 |

### 2.2 JDK 类枚举（`jrt:/` 文件系统）

| 目标 | 结果 | 耗时 |
|---|---|---|
| `/packages/java.util` | 124 类（3 个模块：java.base / java.logging / java.prefs） | **10 ms** 首次，之后 0 ms |
| `/packages/java.time` | 21 类 | 0 ms |
| `/packages` 全量（979 个包） | **14590 类** | **128 ms**（fat jar）/ 96 ms（exploded） |

### 2.3 反射与类加载

| 指标 | 实测 |
|---|---|
| `getMethods()` 热调用 | **0.62 μs / 次** |
| 加载 7 个业务类 | **10 KB** Metaspace（约 1.4 KB/类） |
| `forName(name, **false**, cl)` | **静态初始化块未执行** ✅ |
| `forName(name, **true**, cl)` | 静态块执行 ✅（证明上一行的测试有效） |
| `forName(false)` 后再 `getMethods()` | 静态块仍未执行 ✅ |

### 2.4 跨 JDK 版本一致性（同一 fat jar，JDK 25.0.4 复跑）

| 项 | JDK 17.0.9 | JDK 25.0.4 |
|---|---|---|
| `{pkg}/*.class` | 3 类 / 141–411 μs | 3 类 / 139–359 μs |
| `{pkg}/**/*.class` | 9 类 / 327–593 μs | 9 类 / 248–333 μs |
| `{pkg}/*/`（目录） | 2 | 2 |
| `java/util/*.class` | **0** | **0** |
| `org/springframework/**` | 7014 类 / 29–50 ms | 7014 类 / 19–57 ms |
| jrt `java.util` | 124 类 / 10 ms | 131 类 / 12 ms |
| jrt 全量 | 979 包 / 14590 类 / 128 ms | 1035 包 / 14691 类 / 131 ms |
| `forName(false)` 静态块 | 未执行 | 未执行 |

结构与结论在 17 和 25 上完全一致。JDK 25 的 `java.util` 多 7 个类、总包数多 56 个，
是版本内容差异，不影响方案。

### 2.5 异常输入（安全性预检）

| pattern | 结果 | 耗时 |
|---|---|---|
| `classpath*:../../etc/*.class` | 0 类，不抛异常 | 160–915 μs |
| `classpath*:/etc/*.class` | 0 类，不抛异常 | 111–210 μs |
| `classpath*:{pkg}/**/**/**/**/**/*.class` | 与单层 `**` 相同，无爆炸 | 88–330 μs |
| `classpath*:c/*.class` | 0 类 | 69–668 μs |

结论：Spring 的 resolver 不做路径穿越、不因重复 `**` 放大成本。
但**不能依赖这一点做安全设计**（见 §6）。

---

## 3. 三条决定性结论

### 3.1 `classpath*:` 永远拿不到 JDK 类

两种形态都返回 0 类。JDK 类在 `jrt:` 文件系统里，不是 `file:` / `jar:` 协议，Spring 的
`PathMatchingResourcePatternResolver` 不处理。

**所以必须走两条路：业务类走 `classpath*:`，JDK 类走 `jrt:`。** 这是平台本身的限制，不是可以选的。

### 3.2 目录模式不能用来枚举子包

fat jar 返回 2 个目录且 `exists=true`，exploded 返回 **0**。同一份代码两种结果。

**所以子包候选必须从 `**/*.class` 结果的 FQCN 路径反推**，不能靠目录枚举。
反推是零成本的纯字符串操作：`com.x.shop.order.OrderEnum` 去掉类名 → 包 `com.x.shop.order`，
逐级取父包就得到子包树。

### 3.3 全量类名索引其实很便宜（早期判断是错的）

| 做法 | 成本 |
|---|---|
| 反射加载全部类（我最初评估的 A 路） | 763 ms / 801 类 |
| 只读 `.class` 文件名（`classpath*:`） | **0.3–0.6 ms** / 百级类 |

差三个数量级。原因是前者要建 `Class` 对象、跑解析、连锁加载依赖；
后者只是遍历 jar 的中央目录拿字符串。

**结论：全量类名索引不但便宜，而且是唯一可靠的做法**（§3.2 否掉了按需目录枚举）。
贵的是 `**` 递归到大包（7000 类 / 30–50 ms），所以**索引范围必须锁死在配置的包根，
不能让 HTTP 参数决定 pattern**。

---

## 4. 最终架构

```
                ┌─────────────────────────────────────────┐
                │  索引构建（启动时一次，约 2 ms / 百级类）  │
                ├─────────────────────────────────────────┤
  harken.packages ──► classpath*:{root}/**/*.class ──► 业务类索引
                │                                          │
  jrt:/packages ──► 按包枚举（首次用到该包时）      ──► JDK 类索引
                │                                          │
  ApplicationContext ──► getBeanDefinitionNames()  ──► Bean 名索引
                └─────────────────────────────────────────┘
                                    │
                                    ▼
                ┌─────────────────────────────────────────┐
                │  补全查询（纯内存，微秒级）               │
                │  import 前缀 → 类 / 子包                 │
                │  类简名 + '.' → 静态方法（反射，0.62μs） │
                │  bean 名 + '.' → 实例方法（反射）        │
                └─────────────────────────────────────────┘
```

### 4.1 索引产物

```java
// 服务端产出：按包聚合，供 import 补全
Map<String, List<String>> byPackage;    // "com.x.util" -> ["CalcUtil","GuaByNumber"]

// 前端由 byPackage 反查出来的，不走网络：简名 → 全部同名全名
// （一个都不丢：同名时每个类各占一条候选，选哪条导哪条，不替用户猜）
Map<String, List<String>> fqcnList;   // "CalcUtil" -> ["com.a.CalcUtil","com.b.CalcUtil"]

// 服务端产出：Bean 名 → 类型全名（来自容器内存，零 IO）
Map<String, String> beanTypes;          // "orderService" -> "com.x.order.OrderService"
```

子包不单独存：由 `byPackage` 的 key 反推（纯字符串，见 §3.2）。
`fqcnList` 也不上网：它和 `byPackage` 是同一份信息的两种排法，多传一遍等于把
24 KB 变成 40 KB 换一次两行的循环。

### 4.2 索引构建成本

| 场景 | 实测 |
|---|---|
| 百级业务类（本探针 9 类） | 0.3–0.6 ms |
| 千级（spring-core 470 类） | 3.3–5.6 ms |
| 7000 级（spring 全家，仅作压力参考） | 29–50 ms |
| JDK 单包（java.util 124 类） | 10 ms 首次，之后 0 |
| JDK 全量（14590 类） | 96–128 ms，约 1.7 MB 堆 |

**落地选择：业务类索引在启动时建一次（不懒）**。理由：懒加载只是把 2 ms 推后，
却让「第一次敲点号要等一次网络 + 一次扫描」叠加，而启动时建还能在配置写错时立刻 WARN。
**JDK 按包懒建**，不做全量预建（1.7 MB 常驻不值得，而单包只要 10 ms）。

### 4.3 端点

计划里是两个新端点（`/index` + `/methods`），落地时合成**一个 `/metadata` 的四种形态**。
原因：`/metadata` 已经在给 AI 通道和页面用，再开两个只读端点等于同一份数据三处分发，
三处各自演进就会漂移（`MetadataJson.view()` 现在是唯一分发点，人类通道与 AI 通道共用）。

| 请求 | 返回 | 用途 |
|---|---|---|
| `GET /harken/api/metadata` | `byPackage` + `beanTypes`，**不含方法** | 前端一次拿全，约 24 KB / 800 类 |
| `GET /harken/api/metadata?jdk=java.util` | 追加该 JDK 包的类名 | 懒拉 JDK 包 |
| `GET /harken/api/metadata?type=com.x.MoneyUtil` | 该类的 `public static` 方法（枚举另附 `constants`） | 敲点号时（类本身） |
| `GET /harken/api/metadata?type=MoneyUtil` | 同上，另附 `shadowedBy` | 敲点号时（**没 import** 的简名，见 §4.5） |
| `GET /harken/api/metadata?inst=java.util.List` | 该类型的**实例**方法 | 敲点号时（链式调用的中间结果，见 §4.6） |
| `GET /harken/api/metadata?bean=orderService` | 该 Bean 的实例方法 | AI 通道按 Bean 名查 |

优先级 `type → inst → bean → jdk → 索引`，五个同时给时前者胜。

`?type=` 与 `?inst=` 都带 `typeVars`（如 `List` 的 `["E"]`）：前端拿它把返回类型里的
类型变量换成实参，否则 `get(0)` 之后就跟不下去了。

`?type=` 接受**任意合法类名**，不限制必须在索引里，差异和原因见 §6.3。

### 4.5 `?type=` 的两种形态，以及为什么要区分

带点号 = 全限定名，前端**只在脚本里已经 import 过**的时候这么问，直接给方法表。
不带点号 = 脚本里没 import 的简名，这时服务端多做两件事：

1. 用 `ClassIndex.fqcnOf()` 从索引反查「用户本来想要的那个类」，给它的方法表。
   重名时返回 `null`（不猜）。
2. 用 `MethodCatalog.defaultImportOf()` 按 `java.lang` / `java.util` / `java.io` / `java.net` /
   `groovy.lang` / `groovy.util` 的顺序探测同名类。命中就说明这个名字在脚本里实际绑到的是它，
   写成 `shadowedBy` 一起返回。

为什么必须区分：`Category.allViews()` 里那个 `Category` 绑的是 `groovy.lang.Category`，
不 import 就永远调不到业务枚举的同名方法，而错误信息满屏 `groovy.` 前缀，看不出跟自己代码的关系。
前端拿到 `shadowedBy` 后会在方法列表最上面加一条「补 import」，点它就把 import 补上。

`defaultImportOf` 的结果**连「没有」也缓存**（存空串）：六个 `forName` 都失败也要几微秒，
而这个名字会被反复问到同一个答案。

### 4.6 链式与闭包：光标左边那个值是什么类型

`Category.allViews().forEach(a -> { a.` 里有两个点号，第二个该列什么方法，
取决于 `allViews()` 返回 `List<CategoryView>` 这件事。所以补全的核心问题只有一个：
**光标左边那个值是什么类型**。回答了它，`list.`、`list.get(0).`、`a.` 就是同一个问题。

前端按三步走（见 `app.js` 里的表达式类型推导）：

1. `parseChainAt()` 反向扫出 `a.b().c` 这条链（`?.`、空格、单行内都认）。
2. `chainType()` 沿链逐段查方法表，取上一段方法的返回类型作为下一段的接收者类型；
   类型变量用 `typeVars` 换成实参（`List<Foo>.get(int)` 的 `E` → `Foo`）。
3. 闭包参数（`closureAt()`）取**接收者的元素类型**：`List<X>.forEach(a -> ...)` 里 `a` 就是 `X`。
   Groovy 的 `each` / `collect` 是运行时扩展方法，反射表里没有它们的签名，所以只能按元素类型推。

两种闭包写法都认：Groovy 的 `{ it -> }`（箭头在花括号内）和 Java 风格的 `forEach(m -> { }`
（箭头在花括号前）。Map 的闭包拿键值两个参数（`Map<K,V>` → 第 1 个 K、第 2 个 V）。

方法表是异步拉的，而 `hintSource()` 必须同步返回。所以 `handleDot()` 先 `warmChain()`
把链上每一环拉进缓存，弹窗时全部命中本地表，**网络往返仍然只发生在敲点号那一刻**。

### 4.7 前端时序

```
页面加载 → GET /metadata（24 KB）→ 本地 byName / byType / byPackage / fqcnList
敲 import com.x.Or    → 纯本地过滤 byPackage 的 key 反推子包（0 网络，0 ms）
敲 import java.util.  → GET /metadata?jdk=java.util（26 ms 首次，之后 1.6 ms）
敲 MoneyUtil.         → 没 import → GET /metadata?type=MoneyUtil（2.0 ms 首次）→ 首条是「补 import」
敲 com.x.MoneyUtil.   → 已 import → GET /metadata?type=com.x.MoneyUtil
敲 orderService.      → GET /metadata?inst=com.x.OrderService（1.6 ms）
敲 orderService.topAmount(5). → 链上已缓存（上面那条就是同一个类型）→ 0 网络
                              → GET /metadata?inst=java.util.List（首次 1.8 ms，之后 0）
```

点号是唯一先拉再弹的时机：不拉就弹等于弹一个空列表，比不弹更让人困惑。

**所有网络往返都绑定在用户输入完整名字的那一刻，不绑定在击键上。** 击键路径永远是纯本地。

---

## 5. 和之前两版方案的对比

| 版本 | 类名发现 | 方法表 | 致命问题 |
|---|---|---|---|
| 第一版（全包反射扫描） | 反射 801 类 / 763 ms | 已含 | 慢，且 `+1105` 类常驻 Metaspace |
| 第二版（import 驱动） | 不做 | 按需反射 | 没 import 就没候选，import 本身无法补全 |
| **本版** | `classpath*:` 0.3–0.6 ms | 按需反射 0.62 μs | 无（限制见 §7） |

---

## 6. 安全设计

### 6.1 已实测确认的地基

`forName(name, false, cl)` **不执行静态初始化块**，`getMethods()` 也不触发。
这是整个能力成立的前提，否则读一个类的方法签名就等于执行这个类的初始化逻辑，
补全就变成打开编辑器就跑一遍别人的 `static { ... }` 块。

**代码里绝不允许出现 `forName(name, true, ...)`。** 已有单测锁死：
`MethodCatalogTest.readingStaticMethodsDoesNotRunStaticInitializer` 用一个带 `static { Marker.initializedBy = "yes" }`
的类，读方法表之后断言它仍是 `"none"`。

### 6.2 枚举参数不从 HTTP 来

这是最关键的一条设计决策。pattern 只能由 `harken.packages` 配置拼出，
攻击者无法构造：

- 宽范围 pattern（`**` 到 7000 类 → 30–50 ms，多请求即可打满 CPU）
- 路径穿越（虽然实测 resolver 会安全失败，但**不依赖**它）

`/index?jdk=java.util` 是唯一接受 HTTP 参数的枚举入口，必须：
1. 正则 `^[a-zA-Z_$][\w$]*(\.[a-zA-Z_$][\w$]*)*$`
2. `/packages/{pkg}` 必须 `isDirectory()` 才继续
3. 结果走缓存，重复请求不再扫盘

### 6.3 类加载的上界

计划里写的是 `?type=` 只接受索引里有的简名，能加载的类集合上界等于索引大小。
**落地时放弃了这条**，原因：

能登录 Harken 的人本来就能执行任意 Groovy：`new File("/etc/passwd").text`、`Class.forName(x)`、
`Runtime.getRuntime()` 全都直接可用。限制 `?type=` 只挡住了补全功能，风险一个字节也没减少，
却让「补全一个 `harken.packages` 之外的类」这种正常需求变成不可能。

需要防的是**格式垃圾**，不是类型名本身：

- 数组描述符 `[Ljava.lang.String;` → `forName` 抛 `ClassNotFoundException` 或返回怪东西
- 600 字符的超长串 → 无意义的字符串拼接

所以 `MethodCatalog` 只做两道格式校验（`TYPE_NAME` 正则 + `MAX_TYPE_NAME=500`），
命中就返回空列表，不抛异常。**已实测**：`[Ljava.lang.String;`、`../etc/passwd`、`java/util/UUID`、
600 字符全返回 `methodCount:0`。

Metaspace 的上界因此从「索引大小」变成「用户敲过点号的类的数量」。这个上界仍然可控，
它由页面上的实际操作频率决定，而不是由一次批量扫描决定。

### 6.4 信息面

`/metadata` 四形态都 `requireSession`。类名与方法名不构成新增泄露面，
但仍然需要登录，与其它端点一致。

### 6.5 jrt 只读

`FileSystems.getFileSystem(URI.create("jrt:/"))` 是只读文件系统，写入会抛
`ReadOnlyFileSystemException`，无需额外防护。

拼 `/packages/{pkg}` 之前先用包名正则拦一道（`PACKAGE`），不依赖 `jrt:` 自己的路径校验。
这和 §6.2 用的是同一个判断标准：不指望下游会安全失败。

---

## 7. 已知限制（必须写进用户文档）

1. **索引范围 = `harken.packages`。** 三方 jar 里的类要提示，必须把包加进配置。JDK 类不用配。
2. **`import a.b.*` 通配**：不提示，因为通配没法列出类名。
3. **`import x.Y as Z` 别名**：不提示，别名只存在于源码文本里，索引里没有这个事实。
4. **同名类**：每个同名类各占一条候选，一律显示全限定名（`CalcUtil — com.a.CalcUtil`），
   选哪条导哪条；在写显式 import 之前，对简名敲点号**不弹方法**。
5. **没 import 的简名可能被 Groovy 默认导入抢走**：`Category` → `groovy.lang.Category`。
   补全会在列表最上面给一条「补 import」，并且在选中类名时自动补上 import（见 §4.5）。
6. **链式只在单行内推**：`orderService.listOrders().get(0).getNo()` 支持，但把链拆到多行
   （`orderService\n  .listOrders()`）不弹。返回类型是类型变量又没有实参可换
   （裸 `List` 而非 `List<Foo>`）时也断在那一环，宁可断，不猜。
7. **Groovy 扩展方法不补**：`each` / `collect` / `findAll` 等在 `DefaultGroovyMethods` 里，
   不在 `Class.getMethods()` 的返回中，所以列表里没有它们；但 `{ a -> a.` 的参数推导照样有效
   （按元素类型，不依赖方法表）。
8. **枚举常量只列不改**：`Hexagram.` 会列出 `H1…H64`，点下去插入常量名。
   `valueOf('兑')` 这类按中文名查的写法运行时才会报错，补全管不了，那是业务字段的语义。
9. **类加载不可卸载**（无自定义 ClassLoader 隔离）。但只加载用户敲到点号的类，量级可控。
10. **JDK 方法显示 `arg0`**：JDK 未开 `-parameters`。不影响调用，只是参数名不可读。
11. **未测**：Linux、IDE 热部署下的索引过期。（JDK 21/25 已在 M5 矩阵里跑过，行为一致。）

---

## 8. 实施结果（原估 610 行，实际约 640 行）

| 步骤 | 内容 | 实际落地 |
|---|---|---|
| 1 | `harken-core`：`ClassIndex`（三张表 + 前缀查询 + 子包推导） | `ClassIndex`，约 170 行 |
| 2 | `harken-core`：`JdkPackageIndex`（jrt 按包枚举 + 缓存） | 合进 `ClassIndex.jdkClassesIn()`，不单独成类，因为它只需要一条 `computeIfAbsent` |
| 3 | `harken-spring`：`ClasspathClassScanner`（`classpath*:{root}/**/*.class` → 索引） | `ClasspathClassIndex`，约 130 行 |
| 4 | `harken-web`：`/index`、`/methods` 两个端点 + JSON 转换 | 合进 `MetadataJson`（约 110 行）+ 四形态 |
| 5 | `harken-web`：`app.js` import 补全 + 类名/子包候选 + 按需方法表 | `buildIndex` / `parseChainAt` / `chainType` / `closureAt` / `importCandidates` / `warmChain` |
| 6 | 测试：索引正确性、`forName(false)` 锁死、参数校验、HTTP 断言 | 16 个单测（`ClassIndexTest` 6 + `MethodCatalogTest` 10）+ 4 个集成断言 |

### 8.1 与现有代码的关系

- **`BeanMetadataScanner` / `BeanMetadata` / `MetadataSnapshot` / `StaticClassIndex` 全部删除。**
  它们描述的是「同一个事实的不同切面」，而切面之间的边界是猜的。合并后只剩两个类，
  职责按「要不要反射」切分，不按「数据长什么样」切分：
  - `ClassIndex`：只有字符串，零反射，启动时建
  - `MethodCatalog`：唯一建 `Class` 的地方，敲点号才建
- `MethodSignatures` 原样保留，两边共用签名格式化。
- `HarkenService.beanResolver()` 新增为公开方法，`MethodCatalog` 复用同一份「bean 名 → 类型」结果，
  不再扫第二遍容器。

---

## 9. 落地后的验证记录

1. JDK 17 / 21 / 25 三套矩阵全绿，`jrt:` 与 `classpath*:` 行为不变。
2. 未单独在 Linux 上起 demo：fat jar 形态与 OS 无关，没必要为一次验证去挤端口。
3. 索引规模用 demo 的量级（百级类）验证，未拿真实大体量业务包跑。结论不受影响：
   索引成本由**类文件数**决定，实测 7000 类也只要 29–50 ms。

真实浏览器逐字敲键验证 13 条场景（13/13 通过）：
bean 实例方法、类名候选、注释/字符串/完整词不弹、收窄过滤、显式类型变量、`import` 包级与类级、
JDK 类、非 Spring 静态类。

4. 链式与闭包另跑 19 条（19/19 通过）：`List` 链式方法、
   `get(0)` 经 `E` 还原、`forEach(m -> { m. })` 与 `each { it -> }` 两种闭包、枚举常量
   （`DayOfWeek.` → `MONDAY`）、推不出类型时不弹、以及 MoneyUtil 与普通名字两条回归。
