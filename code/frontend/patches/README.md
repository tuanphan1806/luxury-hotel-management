# braces 3.0.3 depth guard

GHSA-vfj7-8cjw-p6xm (CVE-2026-93687) allows deeply nested patterns to exhaust
the call stack. On 2026-10-03 the npm registry still lists 3.0.3 as latest, and
the latest Next lint plugin still depends on fast-glob -> micromatch -> braces.
In this project this chain comes only from the eslint-config-next devDependency;
application code does not import it or accept user-provided glob patterns.

`pnpm patchedDependencies` applies `braces@3.0.3.patch` on every install and pins
its hash in the lockfile. It caps parser nesting (braces and parentheses) and
the compile, expand and stringify AST walkers at 100 levels. Excessive input
raises a predictable SyntaxError before stack exhaustion. Normal paths,
alternatives, ranges and escaped/quoted literals retain their behavior.

The required frontend test suite resolves the actual copy used by the Next
lint plugin. Regression coverage checks 4096 nested braces, mixed parentheses,
all string APIs, direct deep AST input, and ordinary glob behavior. Removing
or failing to apply the patch causes the regression tests to fail.

OSV reads the unmodified package version, not its patched implementation. The
adjacent `osv-scanner.toml` therefore suppresses only this remediated advisory
for this frontend lockfile until 2026-10-17; it does not exempt this package from
other advisories, dependency/license review, or frontend tests. This is not an
upstream fixed release. Replace the patch with an upstream fix when available,
remove the exception, then rerun lint, tests, build and security checks.

pnpm audit has the same version-only limitation and reports a hypothetical
fixed version >=3.0.4, but querying braces@3.0.4 on 2026-10-03 returns no matching
release. `auditConfig.ignoreGhsas` therefore lists the same single patched ID.
The required regression suite fails after 2026-10-17T00:00Z, enforcing the same
review deadline for pnpm's exception, which has no native expiry field.

References:
- https://github.com/advisories/GHSA-vfj7-8cjw-p6xm
- https://github.com/micromatch/braces/issues/70
