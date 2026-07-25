# Full-system QA bug register

Last updated: 2026-07-26

## Status legend

- `FIXED`: focused regression and complete relevant suite pass.
- `PARTIAL`: implemented or operational, but an external production gate is
  not yet satisfied.
- `GAP`: missing evidence/capability, not a reproduced product defect.
- `TOOL_LIMITATION`: QA tooling issue kept separate from application defects.

## Register

| ID | Severity | Status | Area | Summary |
| --- | --- | --- | --- | --- |
| QA-001 | P2 | FIXED | Frontend build | Native Windows build no longer depends on standalone symlink tracing; clean `next build` passes |
| QA-002 | P1 | FIXED | OAuth/account data | OAuth login now preserves the normal CUSTOMER `CustomerProfile` invariant |
| QA-003 | P1 | FIXED | E2E/session isolation | Parallel stateful tests no longer revoke ADMIN/STAFF single-device sessions |
| QA-004 | P2 | FIXED | E2E/native runtime | Reservation and SePay scenarios can target the native backend through `E2E_QA_API` |
| QA-005 | P3 | FIXED | E2E/dev timing | CUSTOMER access redirect remains strict while allowing Next.js cold compilation |
| QA-GAP-EMAIL-001 | P1 | PARTIAL | Email deliverability | SendGrid accepts and delivers, but Gmail places the message in Spam because production domain authentication is absent |
| QA-GAP-EMAIL-002 | P1 | PARTIAL | Email continuity | Current SendGrid account is a timed trial ending 2026-09-13 |
| QA-GAP-SEPAY-001 | P0 | GAP | Production payment evidence | Real incoming/outgoing bank transfers were not executed in this QA wave |
| QA-TOOL-001 | P3 | FIXED | Connected Chrome | Chrome control recovered and was used for real OAuth and SendGrid inspection |

## QA-001 — Native Windows build

`next.config.mjs` selects the normal artifact on Windows and retains standalone
output for Linux/Docker or explicit opt-in. A clean native production build
compiled, typechecked, generated 42 routes and emitted `.next/BUILD_ID`.

## QA-002 — OAuth profile invariant

The OAuth service now ensures a `CustomerProfile` for:

- an existing OAuth mapping;
- a verified Google account linked by authoritative email;
- a newly created OAuth CUSTOMER.

Focused unit/integration tests and the complete 321-test backend suite pass.
The same provider subject continues to resolve to the same user account.

## QA-003 — Stateful account test isolation

ADMIN and STAFF intentionally permit one active device. Running stateful specs
on multiple workers caused false 401 responses when one worker revoked another
worker's token. Playwright now uses one worker; the complete E2E run passes.

## QA-004 — Native QA backend target

The two financial runtime specs no longer require a hardcoded port `18080`.
They use `E2E_QA_API` with the old port as a compatibility fallback. The full
run against native backend `8080` passes.

## QA-005 — CUSTOMER dashboard redirect timing

The first local visit to `/account` required a cold Next.js compilation that
could consume almost all of the default five-second URL assertion. A focused
rerun proved CUSTOMER content and routing were correctly protected. Only the
test timeout was increased to 15 seconds; authorization code was not changed.

## QA-GAP-EMAIL-001 — SendGrid messages reach Spam

Evidence:

- SendGrid returned HTTP 202 and Gmail received the verification email.
- The message appeared in Gmail Spam.
- The configured From domain is `gmail.com`.
- SendGrid Sender Authentication shows no authenticated domain and describes
  Single Sender as testing-only.

The root fix requires a domain controlled by the project and matching
SPF/DKIM/DMARC records in SendGrid. Template or CSS changes alone cannot fix
domain alignment. `luxury-hotel.publicvm.com` is not being adopted.

## QA-GAP-EMAIL-002 — Timed SendGrid trial

The SendGrid account UI reports that the trial ends on 2026-09-13. Keep the
current integration for development, but select a sustainable production email
provider/plan before that date. Do not expose or broaden the existing
mail-send-only API key.

## QA-GAP-SEPAY-001 — Real bank evidence

Automated incoming/outgoing, matching, underpayment, overpayment, refund and
replay tests pass. A forged production webhook is rejected with HTTP 401.
Production DoD still requires one controlled real incoming transfer and one
controlled real outgoing refund, followed by ledger/reconciliation checks.
