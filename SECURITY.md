# Security policy

## Supported version

Security fixes are applied to the current production branch (`main`). Older
branches and local snapshots are not supported release channels.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability or include production
credentials, personal data, payment details, webhook secrets, or exploit steps
in a public discussion.

Use GitHub's private vulnerability reporting for this repository:

https://github.com/tuanphan1806/luxury-hotel-management/security/advisories/new

Please include the affected route or component, impact, safe reproduction
steps, and any relevant request correlation ID. Use test data only. The project
owner will acknowledge the report, assess severity, and coordinate a fix before
public disclosure.

## Production secrets

Secrets belong in the deployment providers' environment-variable stores. They
must not be committed to source control, copied into issues, or placed in test
fixtures. A disclosed credential must be revoked and rotated immediately.
