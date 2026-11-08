# Spring Web applicability review — 2026-11-08

The container scanner reports four findings for three CVEs in Spring Web
6.2.19. The framework is not patched for these CVEs. This application does not
enable the vulnerable features described by the vendor:

- CVE-2026-47884 requires XsltView and implicit view rendering under a catch-all
  mapping. This backend has no XSLT views/resolvers; application controllers
  use response-body RestController endpoints.
- CVE-2026-47890 requires view fragments sent over SSE. The API and chatbot
  return JSON and do not send SSE view fragments.
- CVE-2026-47892 requires WebFlux functional server endpoints. WebFlux is used
  for the outbound WebClient; no functional server RouterFunction is installed.

`SpringWebExposureIntegrationTest` inspects the application context, route
annotations, declared response types and media types. It fails when these
assumptions change. The test runs in the required backend suite before the
container scan. Source inspection also found no application use of the
affected classes or functional routing APIs.

`code/backend/spring-web.openvex.json` records **not_affected**, not **fixed**,
for only these CVEs and exact Spring artifact versions. It is loaded only by
this backend image scan. Other CVEs and High/Critical thresholds remain
enforced; the APT image vulnerability is actually patched in the Dockerfile.
Do not copy this VEX to another application or expand its version range.

Reassess before adding any server-rendered views, SSE/streaming handlers,
functional routes, profile-specific web configuration or new web framework
versions. Remove the corresponding statements after migrating to a supported
fixed Spring release. Spring lists 6.2.20 as commercial support and 7.0.9 as
an OSS fix; a Boot 4 migration needs separate compatibility and domain testing,
not an isolated Spring 7 override in Boot 3.

Sources:

- https://spring.io/security/cve-2026-47884/
- https://spring.io/security/cve-2026-47890/
- https://spring.io/security/cve-2026-47892/
- https://github.com/aquasecurity/trivy/blob/main/docs/guide/supply-chain/vex/file.md
