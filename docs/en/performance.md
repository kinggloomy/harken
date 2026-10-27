# Performance

**English** | [简体中文](../zh-CN/performance.md)

> Reproduce with: `mvn -q -DskipTests package && bash scripts/bench.sh`
> Every number below can be measured again with that one command. No "lab environment"
> adjectives that cannot be re-checked.

- Measured: 2026-10
- Machine: Debian 13 · x86_64 · 4 cores · 7.9 GB RAM (VM)
- Runtime: JDK 17 (G1, default heap)
- Shape under test: Spring Boot starter + H2 + real transactions (`harken-demo` executable jar)

---

## 1. End-to-end latency

Each measurement is a full `POST /harken/api/execute`: HTTP → session check → same-origin check →
Groovy compile (or cache hit) → transaction → result serialization.

| Scenario | Mean | Notes |
|---|---|---|
| **First execution** (cold JVM, includes Groovy init) | **73 ms** | Run 1; runs 2–3 already drop to 19 ms |
| **Same script 50 times** (compile cache hit) | **4.1 ms** | The everyday path |
| **30 different scripts** (always recompiles) | **15.4 ms** | Worst case: an AI writing a new script each time |

How to read these:

- **The 4.1 ms vs 15.4 ms gap is the entire value of the compile cache.** Keyed by script
  SHA-256, LRU of 128.
- **73 ms is what "the first click" feels like.** It is not eight times worse than 4 ms in
  practice; most of it is Groovy's own class initialization, paid once per process.
- These include HTTP and serialization. A micro-benchmark of "Groovy compile" alone would look
  better, but users experience end-to-end, so that is what gets measured.

---

## 2. Memory

| Metric | Measured |
|---|---|
| Metaspace (after startup, before the benchmark) | 49.5 MB |
| After 500 distinct scripts | 55.0 MB (**+5.5 MB**) |
| After another 500 distinct scripts | 57.0 MB (**+2.0 MB**) |
| Heap (G1, used) | ~76 MB / 120–165 MB total |
| Resident, single process | ~350 MB RSS |

**Conclusion: Metaspace converges; it does not leak linearly.**

The first batch adds 5.5 MB, the second only 2.0 MB (about 36% of the first). Two reasons:

1. The compile cache is an LRU of 128, so old `Script` objects get dropped;
2. Dropped classes become unreachable together with their `ClassLoader`, and Metaspace is
   reclaimed at the next Full GC.

That said, **"it never grows" would be false**: a sustained load of brand-new scripts will still
show growth until GC catches up. Mitigations: lower `max-script-length`, prefer a fixed catalog
in `RESTRICTED` mode, restart periodically if needed.

> This conclusion was **revised after measurement**. The first benchmark ran one batch of 500,
> showed +5.5 MB, and the write-up was heading toward "roughly linear, restart periodically".
> A second batch revealed convergence. One data point gives you a slope; two are needed to tell
> "linear" from "converging".

---

## 3. Completion metadata

| Metric | Measured |
|---|---|
| Full index (demo's bean count, uncompressed) | 1.3 KB |
| Full index latency | 1–4 ms |
| One bean's method table (first time) | ~2 ms |
| Same method table (client cache hit) | 0 ms, no network |

Real applications may have hundreds of beans and thousands of classes, pushing the index into tens
of KB. That is exactly why gzip exists (`codemirror.js` measured 402 KB → 107 KB, −73%; the index
is the same kind of text and compresses similarly).

Method tables are fetched on demand: typing a dot asks about one name, not the whole application.

---

## 4. Compared to "not using Harken" (honest version)

Harken adds cost in two places; the rest is constant:

| Stage | Cost | Notes |
|---|---|---|
| Groovy compile | ~11 ms per new script | Near zero on cache hit |
| Transaction wrap + audit write | sub-millisecond | Audit uses a separate connection, outside the business transaction |
| Row guard (`GuardedDataSource`) | one counter increment per `executeUpdate` | Dynamic proxy; does not change SQL semantics |

So "run one query through Harken" versus "run the same query from your code" differs by
**single-digit milliseconds**, in exchange for rollback, audit, a row ceiling and a read-only AI
channel. For anything that writes production data, that trade has no real downside.

---

## 5. What was not measured

Being explicit beats pretending to be thorough.

- **Concurrency / throughput**: no load test. Harken targets "a human plus a few AI clients doing
  low-frequency operations", not a high-QPS gateway. Concurrency behavior depends on your beans
  and your database, not on Harken.
- **Long-run stability**: a 1000-execution short run only; no 24-hour soak. The Metaspace
  convergence above is a short-run result and still deserves long-term observation.
- **JVM differences**: performance measured on JDK 17 only. JDK 21/25 were verified for functional
  correctness (`scripts/jdk-matrix.sh`), not benchmarked. Measure the payoff with your own
  business scripts if you plan to switch.
