# Isolated Render cold-start control

Diagnostic branch only. Do not merge or deploy this image to the hotel backend.

Use only the existing `luxury-hotel-backend-recovery` Free service with scheduling
and seeds disabled and with no frontend, OAuth or webhook traffic routed to it.
The process reads only `PORT`, uses JDK HTTP server, and never accesses a database,
provider API, user data or credential. Other inherited variables are not read by
the application. Keep the existing service configuration for rollback.

Build context remains `code/backend`; Dockerfile path is
`./diagnostics/cold-start/Dockerfile`. The initial control retains production's
`EXPOSE 8080` metadata, the service's runtime PORT and Java options. Health path
remains `/actuator/health/readiness`. Only GET/HEAD on that path or `/` returns
200. Other paths return 404, and all write methods return 405.

Record the warm `bootId` and last request time. Leave the service without traffic
for at least 16 minutes, confirm the sleeping response on `/robots.txt`, then
measure the first readiness request. A new `bootId` and recent `startedAt` prove
a fresh process. Do not infer a successful cold wake from a warm 200 or deploy.

If this control fails, Spring/JPA and Neon are absent from that failed run. That
narrows the cause but does not by itself prove a specific Render-internal fault.
If it passes, restore the hotel application and investigate startup dependencies
and timing; the control is not a replacement hotel API.

Rollback while suspended: restore branch `main`, Dockerfile `./Dockerfile`,
context `.`, root `code/backend`, and `/actuator/health/readiness`. Keep Free,
seeds/scheduling disabled, and existing database/credentials/integrations intact.
