# Scripting and completion

**English** | [简体中文](../zh-CN/scripting.md)

The console runs Groovy. This page covers three things: what a script can see, what execution
actually means, and how far completion goes (and what it deliberately refuses to do).

---

## 1. What a script can see

**A bean name is a variable name.** A bean called `orderService` in the container is `orderService`
in the script. Resolution is lazy: only names you reference are fetched from the context.

```groovy
return orderService.count()
```

**`println` is captured.** Output goes into the response `output` field, not to your application's
stdout:

```groovy
println "starting"
def n = orderService.count()
println "$n rows"
return n
```

The cap is `harken.output-max-bytes` (32 KB by default); overflow is truncated and flagged.

**You can import your own classes.** The script's parent classloader is your application's:

```groovy
import com.example.order.OrderStatus

return orderService.listByStatus(OrderStatus.NEW).size()
```

**There is no hidden `harken` variable**, no preset utility object, no DSL. It is your classes
plus Groovy.

---

## 2. Execution semantics

| Item | Behavior |
|---|---|
| Transaction | Wrapped in `REQUIRES_NEW`. `commit:false` → guaranteed rollback |
| No transaction manager | Both commit and dry run are **refused** (`HARKEN_NO_TRANSACTION`), never degraded |
| Timeout | `harken.timeout-ms`, interrupts the thread (`ThreadInterrupt` AST transform, works inside loops) |
| Compile cache | Keyed by script SHA-256, LRU 128. Re-running the same script ≈ 4 ms |
| Return value | JSON-serialized, capped at `harken.result-max-bytes`; non-serializable values fall back to `toString` |
| Audit | Written **outside** the transaction; the rolled-back run is in there too |

### Dry run vs commit

```json
{ "script": "return orderService.markOne('SO-1001')", "commit": false }
```

| | Dry run | Commit |
|---|---|---|
| `commit` | `false` | `true` |
| Executes | Yes, for real | Yes, for real |
| Persists | No, rolled back | Yes |
| Role required | `OPERATOR` | `ADMIN` |

A dry run is **not** "compile only" and **not** "simulate". It really runs, then rolls back.
For syntax only: `POST /api/compile`, which does not execute.

> Read your data back after a dry run. `rolledBack=true` is the engine's self-report; reading it
> back is the evidence.

---

## 3. Completion

The whole mechanism reduces to one question: **what is the type of the value left of the cursor?**

### Six triggers

| You type | You get |
|---|---|
| `orderService.` | Instance methods with real parameter names and return types |
| `MoneyUtil.` | Static methods (no bean needed) |
| `Hexagram.` | Enum constants first, then `values()` / `valueOf()` |
| `com.example.MoneyUtil.` | Same, via fully-qualified name |
| `orderService.topAmount(5).` | `List` methods |
| `import java.util.` | Class names in that JDK package |

### Chains: follow the return types

`returns` carries full generics, so a chain resolves segment by segment:

```groovy
orderService.topAmount(5).            // List<Map<String,Object>>
orderService.topAmount(5).get(0).     // Map<String,Object>
```

Type variables (`E`, `K`, `V`) are substituted with the actual arguments, so a signature reads
`add(java.util.Map<java.lang.String, java.lang.Object>)` instead of `add(E)`.

### Closure parameters: from the receiver's element type

```groovy
orderService.topAmount(5).forEach(m -> {
  m.                                  // m = Map → get / put / keySet / entrySet …
})

orderService.listOrders().each { it -> // Groovy style resolves the same way
  it.
}

Map<String,Integer> m = ...
m.each { k, v ->                       // Map yields two parameters: K and V
  v.
}
```

The second parameter of `eachWithIndex` is hard-coded to `Integer`.

### Missing imports: the first suggestion handles it

Type a simple name that is not imported:

```groovy
Category.        // first entry: "add import com.example.Category + complete name"
```

Selecting it inserts the import **and** completes the name in one operation, so a single undo
reverts both.

The import goes after the last existing `import` line, the position least likely to disturb
anything.

### Name collisions: refuse, do not guess

If the simple name is already bound by another explicit import, no "add import" entry appears,
only a note. The reason is practical: `Category` might be `groovy.lang.Category` (a Groovy default
import) or `com.example.Category`. Inserting the wrong one compiles fine and behaves wrong, which
is ten times harder to diagnose than an error.

For the same reason, a simple name that exists in multiple packages gets **no method hints** until
you write an explicit import.

### What it will not do

| Case | Why not |
|---|---|
| Cross-line chains (`orderService` newline `.topAmount()`) | The parser scans backwards on the current line only; spanning lines means re-deriving bracket matching for the whole expression at a poor cost/benefit |
| Raw `List` with no type argument | No arguments to substitute; the element type is unknowable |
| `import x.Y as Z` aliases | Resolving `Z` requires an alias table; not implemented, and not pretended |
| `def x = ...` then `x.` | The type is only knowable by executing; no guessing |
| Picking one overload to infer a return type | Picking is guessing, and a wrong answer is worse than none |

The rule: **if it cannot be inferred, nothing is shown.** Suggesting another class's methods is
worse than showing nothing.

---

## 4. Common patterns

```groovy
// One row
return orderService.byNo("SO-1001")

// A few fields from several rows (returned value gets JSON-ified)
return orderService.recent(10).collect { [no: it.no, status: it.status] }

// Before/after around a write
def before = orderService.statusOf("SO-1001")
orderService.markOne("SO-1001")
return [before: before, after: orderService.statusOf("SO-1001")]   // identical in a dry run

// Sort a frequency map
return orderService.countByStatus().sort { -it.value }

// Group and count (plain Groovy)
return orderService.recent(100)
        .groupBy { it.status }
        .collectEntries { status, rows -> [status, rows.size()] }
```

When unsure about a signature, type the dot. Completion reads it off the class; docs cannot
promise that.

---

## 5. Interaction with audit

Every execution writes one audit record:

| Field | Content |
|---|---|
| `trace` | Trace id |
| `operator` / `ip` | Who, from where |
| `commit` / `rolledBack` | Requested commit, actually rolled back |
| `ok` / `errType` / `err` | Outcome, exception type and message |
| `ms` / `rows` | Duration, affected rows |
| `digest` | First 16 hex chars of the script's SHA-256 |
| `script` | Script body, sensitive values masked as `***` |

Guard rejections land in the same table with `ok=false` and `errType=HARKEN_ROW_LIMIT`. That is
how you find out who is touching a critical table.

Read it via `GET /harken/api/audits?limit=50` or the "recent executions" panel.

Next: [HTTP API](http-api.md) · [Security model](security.md)
