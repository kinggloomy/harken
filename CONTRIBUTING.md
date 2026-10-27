# Contributing to Harken

Thanks for taking the time. This file lists the rules that decide whether a patch lands. The rest
is normal open-source etiquette.

---

## Ground rules that will get a patch rejected

These are not preferences. Most of them are enforced by a test or a build rule, and breaking one
changes what the product is.

| # | Rule | Why it exists |
|---|---|---|
| 1 | **`harken-core` must never depend on Spring.** Enforced by the Maven Enforcer rule `forbid-spring-in-core`. | "Works without Spring" has to be a structural fact. The moment one `spring-context` import appears, it becomes a promise. |
| 2 | **The AI channel must stay read-only by type.** Do not add a `commit` field, a flag, or an `ai.read-only` switch to `ReadOnlyRequest`. | A read-only guarantee you can turn off is a fake guarantee. The first urgent requirement flips it. |
| 3 | **Audit writes must stay outside the business transaction.** | The rolled-back execution is precisely the one you need on record. |
| 4 | **No transaction ⇒ refuse commit and dry run.** Never degrade to "run anyway with a warning". | "Reported as rolled back but actually persisted" is worse than a failed request. |
| 5 | **No sandbox claims.** Do not add interception of `Runtime.exec`, file access, UA sniffing, or an "is this really an AI" detector. | It cannot be done reliably, and a partial blocklist creates false confidence. See the security model, gap G1. |
| 6 | **No speculative abstraction.** Two concrete usages before an interface; three before a factory. | Every layer added for a hypothetical future has to be read by everyone who touches the code. |

## Build and test

```bash
mvn clean install          # 7 modules, full test suite
mvn -pl harken-core test   # one module
```

Requirements: JDK 17+ and Maven 3.6+. The build fails at `validate` on a wrong JDK with a message
that says so, rather than producing confusing compile errors later.

Useful scripts:

| Script | What it does |
|---|---|
| `scripts/pen-test.sh` | Starts three instances and runs the nineteen penetration cases, then cleans up |
| `scripts/bench.sh` | Reproduces the numbers in [the performance page](docs/en/performance.md) |
| `scripts/jdk-matrix.sh` | Runs the suite on JDK 17 / 21 / 25 |
| `harken-demo/scripts/http-smoke.sh` | HTTP smoke suite against a running demo |

### Test expectations

- **New behavior needs a test.** A bug fix needs a test that fails before the fix.
- **Assertions should name the invariant, not the implementation.** A test that breaks when a
  method is renamed for good reason is a liability.
- If a test needs a database, use H2 by default; the PostgreSQL cases are conditionally skipped
  when no server is configured.

## Code style

- Java 17, `-parameters` is on (parameter names are part of the completion feature).
- Comments explain **why**, in the language the surrounding file already uses. Do not translate an
  existing file's comments just to change their language.
- Javadoc on public types and non-obvious methods. The generated API docs are published, so they
  are user-facing.
- No new runtime dependency without a reason in the pull request. Current runtime set: Groovy,
  SLF4J, Jakarta Servlet API. (Spring is optional and lives only in `harken-spring*`.)

## Commits and pull requests

- One logical change per commit. Conventional-commit prefixes are appreciated
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `build:`), not required.
- PR description should state: what breaks without this change, what you did, how you verified it.
  "It compiles" is not verification for anything UI-related — attach what you saw.
- Keep the diff reviewable. Unrelated formatting changes in the same PR make review slower and
  obscure the real change.

## Documentation

Documentation is part of the change, not a follow-up. Two languages, kept in sync:

| English | 简体中文 |
|---|---|
| `README.md` | `README.zh-CN.md` |
| `docs/en/*.md` | `docs/zh-CN/*.md` |

If a change alters behavior described in one language, update both. If you can only write one,
say so in the PR and leave the other marked — silently letting them drift is the worst option.

## Reporting bugs

Include: version, `/harken/api/health` output, the script or request, expected vs actual. If it is
a security issue, follow [SECURITY.md](SECURITY.md) instead of opening an issue.

## License

By contributing you agree your work is licensed under the [Apache License 2.0](LICENSE).
