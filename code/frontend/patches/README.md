# braces 3.0.3 depth guard

Reviewed on 2026-11-08: GitHub withdrew GHSA-vfj7-8cjw-p6xm on 2026-10-23
because it is not a practically exploitable vulnerability. The expired
2026-10-17 exceptions in pnpm and OSV have been removed, together with their
calendar-only expiry test. No advisory is suppressed for this patch.

`pnpm patchedDependencies` applies `braces@3.0.3.patch` on every install and pins
its hash in the lockfile. It caps parser nesting (braces and parentheses) and
the compile, expand and stringify AST walkers at 100 levels. Excessive input
raises a predictable SyntaxError before stack exhaustion. Normal paths,
alternatives, ranges and escaped/quoted literals retain their behavior.

The required frontend test suite resolves the actual copy used by the Next
lint plugin. Regression coverage checks 4096 nested braces, mixed parentheses,
all string APIs, direct deep AST input, and ordinary glob behavior. Removing
or failing to apply the patch causes the regression tests to fail.

The behavioral tests remain mandatory. pnpm audit, OSV and dependency/license
review continue to cover this package and all other dependencies without a
version-only exception. The patch is retained as defensive hardening, not an
upstream fixed release. Application code does not import braces or accept
user-supplied glob patterns. When replacing the patch with an upstream
implementation, verify the same behavioral cases and rerun lint, tests, build
and security checks.

References:
- https://github.com/advisories/GHSA-vfj7-8cjw-p6xm
- https://github.com/micromatch/braces/issues/70
