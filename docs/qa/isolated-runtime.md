# Isolated runtime verification

Use `compose.qa.yml` for stateful browser/API tests. The ordinary Compose file
uses the developer's persistent PostgreSQL volume and must not be used here.

The QA backend runs the production profile with mandatory pricing quotes, but
has synthetic seeded users, a separate PostgreSQL volume, local upload storage,
test-only signing/encryption keys, and no email, Gemini, or SePay credentials.
Its only Docker network is internal, so it cannot call external providers.
A fixed Nginx proxy publishes only `127.0.0.1:19080`; backend and database remain
on the internal network. This also supports Docker Desktop, which does not
publish ports directly from a container attached only to an internal network.

From the repository root:

```powershell
docker compose -f compose.qa.yml up -d --build --wait --wait-timeout 240
cd code/frontend
node node_modules/@playwright/test/cli.js test --config=playwright.qa.config.ts
cd ../..
powershell -ExecutionPolicy Bypass -File scripts/verify-qa-restore.ps1
```

The Playwright setup inspects the live containers, datasource, volume, network,
test provider configuration, and loopback binding before any mutating test.
These specs intentionally refuse the general Playwright configuration. The QA
frontend on port 13000 must not already be running; the runner starts its own
instance with the isolated backend URL. Tests execute serially because privileged
accounts allow only one active device session.

Coverage includes role/session boundaries, catalog/media ownership, walk-in,
fee replacement, cash settlement, immutable invoice, checkout replay, 50%/100%
online payment, capacity hold competition, HMAC rejection/replay, underpayment,
overpayment, and outgoing refund events. SePay events are synthetic, not real
bank transfers. Keep human operator sign-off and real-provider evidence separate.

The restore script stops only the QA backend, exports the synthetic database,
restores into a uniquely named temporary database, compares every public table's
row count and sorted row digest, and runs post-cutover constraint/sequence checks.
It removes only its temporary restore database and restarts the QA backend.
Evidence and the synthetic dump go to `output/isolated-qa-2026-09-25/`.
This is not a backup of Neon or measured production RPO/RTO.

After recording evidence, remove only the disposable QA stack:

```powershell
docker compose -f compose.qa.yml down --volumes
```

Never run that cleanup against `compose.yml`. Repeat from a fresh QA volume for
an independent run; financial fixtures are deliberately retained until cleanup.
