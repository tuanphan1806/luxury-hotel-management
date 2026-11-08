# Free hosting recovery

## Verify each layer

Run the **Check Render backend manually** GitHub Actions workflow only when
investigating a failure. It waits up to 210 seconds for Render readiness, then
checks aggregate health, the database-backed catalog and the public Vercel
catalog proxy. Failure of any step fails the workflow. A frontend homepage 200
does not establish backend or database health.

Public endpoints:

- Frontend: https://www.luxuryhotelvn.site/
- Readiness: https://luxury-hotel-backend-c06y.onrender.com/actuator/health/readiness
- Database health: https://luxury-hotel-backend-c06y.onrender.com/actuator/health
- Direct catalog: https://luxury-hotel-backend-c06y.onrender.com/api/room-types
- Public catalog: https://www.luxuryhotelvn.site/catalog_proxy/room-types

The browser retries transient catalog reads for at most 210 seconds, with
increasing waits capped at 20 seconds, and respects Retry-After. Concurrent
readers share requests. Permanent errors fail immediately; a prolonged outage
offers a manual reload. Booking, payment and authentication writes are not
automatically repeated by this recovery logic.

## Recover a stuck Render service

1. Compare the latest service logs with the reported deployment status. Check
   Neon usage and compute state before attributing every timeout to quota.
2. If the service stays unreachable beyond its cold-start window and restart
   does not produce a new startup, deploy the exact last known-good commit.
   Keep the current database and secrets. Do not run seed/reset operations.
3. Confirm startup, successful Flyway validation and all four endpoint checks.
   Then load the rooms page in a browser and check an authentication request.
4. A specific-commit deploy disables Render auto-deploy. Restore **After CI
   Checks Pass** after recovery; never bypass a failed release gate.
5. Leave the service without traffic for at least 15 minutes, then verify a
   fresh request wakes it. A successful manual redeploy alone is not proof
   that the next idle cycle will recover.

## Preserve the Free budget

Keep the Render health-check path on `/actuator/health/readiness`, which does
not query Neon. Keep production Hikari minimum idle and keepalive at zero,
and allow idle connections to close. Keep recurring keep-awake workflows and
database probes disabled. Do not use UptimeRobot to keep the application and
its database jobs running continuously.

Render Free can sleep when idle and can start slowly. The frontend can recover
from a normal cold start; it cannot repair a provider outage or guarantee
always-on availability. Choose paid always-on compute only with an explicit
budget decision. Keep periodic database backups through an approved process.

## Rollback

Promote the previous known-good Vercel deployment if a frontend change fails.
For a backend regression, deploy the previous compatible application commit
without down-migrating or resetting production data. Recheck readiness,
aggregate health and both catalog paths, and restore auto-deploy after a
specific-commit recovery. Never copy local `.env` files or secrets into logs,
issues or this document.
