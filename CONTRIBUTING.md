# Contributing

This repository uses a three-environment promotion model. Long-lived branches are protected and changes move forward through pull requests.

## Long-lived branches

| Branch | Purpose | Deployment target |
| --- | --- | --- |
| `dev` | Integration of completed work | Development/UAT environment |
| `main` | Stable release candidate | Staging/pre-production |
| `production` | Approved live release | Production |

`production` is not a working branch. Never develop directly on `production`, `main`, or `dev`.

## Short-lived branches

Create a branch from the latest `dev` using one of these prefixes:

- `feature/<ticket-or-scope>` for a new capability.
- `fix/<ticket-or-scope>` for a defect found before production.
- `refactor/<scope>`, `test/<scope>`, `docs/<scope>`, or `chore/<scope>` for non-feature work.
- `release/<version>` when a release needs stabilization between `dev` and `main`.
- `hotfix/<incident-or-scope>` from `production` only for an urgent live defect.

Use lowercase names with digits, dots, underscores, or hyphens. Examples: `feature/refund-webhook`, `fix/checkout-race`, `release/1.0.0`.

## Promotion flow

```text
feature/*, fix/*, refactor/*, test/*, docs/*, chore/*
                            |
                            v
                           dev
                            |
                            v
                           main
                            |
                            v
                        production
                            |
                            v
                         vX.Y.Z tag
```

1. Rebase or merge the latest `dev` into the short-lived branch.
2. Open a pull request into `dev` and complete the pull-request template.
3. Merge only after the branch-policy, backend/PostgreSQL, and frontend checks pass.
4. Promote `dev` to `main` with a separate pull request after integration testing.
5. Promote `main` to `production` only after staging/UAT approval.
6. Tag the accepted production commit using semantic versioning, for example `v1.0.0`.

For a hotfix, branch from `production`, merge the fix into `production`, and then apply the same hotfix to `main` and `dev` before deleting the branch.

## Commit and pull-request policy

- Use Conventional Commit subjects such as `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, or `chore:`.
- Keep commits focused and do not mix unrelated formatting or generated files into a functional change.
- Never commit `.env` files, credentials, provider secrets, production data, logs, or build caches.
- Describe database migrations, API changes, financial behavior, rollback steps, and manual verification in the pull request.
- Financial and reservation changes must preserve idempotency, ledger integrity, room-hold behavior, and concurrency protections.
- Delete short-lived branches after merge.

## Local quality gates

Run the relevant checks before opening a pull request:

```powershell
Set-Location code/backend
.\mvnw.cmd -Ppostgres-migration-test verify

Set-Location ../frontend
pnpm install --frozen-lockfile
pnpm run lint
pnpm run build
```

Production deployment remains a separate approval step even when all automated checks pass.
