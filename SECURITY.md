# Security Policy

Harken runs arbitrary Groovy inside a live JVM, so a bypass here matters more than a cosmetic bug.
Reports are read and answered.

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private vulnerability reporting
(*Security → Report a vulnerability* on this repository), or email
<mailto:me@jintienan.com>.

Please include:

- Harken version
- The output of `GET /harken/api/health`
- A reproduction script or exact request
- What you expected versus what happened

## What to expect

| Stage | Commitment |
|---|---|
| Acknowledgement | Within 3 business days |
| Initial assessment | Within 7 days |
| Fix for a confirmed high-severity issue | Target 30 days, coordinated disclosure date agreed with you |

Credit is given in the release notes unless you prefer otherwise.

## Scope

In scope:

- Authentication, session handling, IP allowlisting, same-origin checks
- Request signature verification (timestamp window, HMAC, nonce replay)
- Channel confusion between the human console and the AI channel
- Privilege escalation between `OPERATOR` and `ADMIN`
- Write-guard bypass (row ceiling, table denylist, DDL lock)
- Audit tampering, or credentials leaking into audit records and logs
- Anything that turns the read-only AI channel into a writer

Out of scope, by design — see [the security model](docs/en/security.md) §2 for the full list:

- Arbitrary code execution reachable **through a legitimate commit credential**. Scripts run with
  the application's own authority; that is the product, not a bug. Give commit rights only to
  people you would let `ssh` into the box.
- `Runtime.exec`, file access, or `System.exit` being available to a committed script (gap G1).
- Table names assembled at runtime bypassing the denylist (gap G2).
- Denial of service through a script that consumes memory within the configured timeout.
- Issues that require an already-compromised host or a leaked `harken.ai.token`.

If you are unsure whether something is in scope, report it and say so.

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| < 1.0 | No |

## Hardening checklist

Before exposing Harken anywhere, confirm:

1. `harken.web.allowed-ips` is not `*`.
2. `harken.web.mode=RESTRICTED` in production, with a reviewed script catalog.
3. `harken.guard.max-affected-rows` and `deny-tables` are set (defaults are permissive).
4. The application's database account has `SELECT` only.
5. `harken.ai.token` is set from an environment variable, signing is enabled, and the token is
   rotated by restarting with a new value.

Details: [security model](docs/en/security.md) · [部署与安全基线](docs/zh-CN/security.md)
