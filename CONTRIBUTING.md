# Contributing

This repository uses a two-branch promotion model. Long-lived branches are protected and changes move forward through pull requests.

## Long-lived branches

| Branch | Purpose | Deployment target |
| --- | --- | --- |
| `develop` | Integration of completed work | Development/UAT environment |
| `main` | Approved, deployable release | Production |

Never develop directly on `main` or `develop`. Staging validation uses the release candidate from `develop`; production deploys only from `main` or an immutable tag created from `main`.

## Short-lived branches

Create a branch from the latest `develop` using one of these prefixes:

- `feature/<ticket-or-scope>` for a new capability.
- `fix/<ticket-or-scope>` for a defect found before production.
- `refactor/<scope>`, `test/<scope>`, `docs/<scope>`, or `chore/<scope>` for non-feature work.
- `release/<version>` when a release needs stabilization between `develop` and `main`.
- `hotfix/<incident-or-scope>` from `main` only for an urgent live defect.

Use lowercase names with digits, dots, underscores, or hyphens. Examples: `feature/refund-webhook`, `fix/checkout-race`, `release/1.0.0`.

## Promotion flow

```text
feature/*, fix/*, refactor/*, test/*, docs/*, chore/*
                            |
                            v
                         develop
                            |
                            v
                           main
                            |
                            v
                         vX.Y.Z tag
```

1. Rebase or merge the latest `develop` into the short-lived branch.
2. Open a pull request into `develop` and complete the pull-request template.
3. Merge only after the branch-policy, backend/PostgreSQL, and frontend checks pass.
4. Promote `develop` to `main` with a separate pull request after sprint review and staging/UAT approval.
5. Deploy the accepted `main` commit and tag it using semantic versioning, for example `v1.0.0`.

For a hotfix, branch from `main`, demo and verify the fix, merge it into both `main` and `develop`, deploy `main`, and then delete the hotfix branch.

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
