# Brevo transactional email

The backend supports `EMAIL_PROVIDER=brevo` over HTTPS. Verification, password
reset, booking confirmation, contact replies and audit alerts use the existing
versioned HTML/plain-text templates. SendGrid template IDs are ignored for Brevo.
The default remains `sendgrid`, so deploying the adapter does not switch providers.

## Activate on Render

1. Verify the sender in Brevo and confirm transactional sending is enabled.
2. Generate a dedicated API key named `luxury-hotel-render-production` with an
   explicit expiry. Obtain approval before creating persistent access or moving
   the credential into Render. Never paste the key into chat, Git or logs.
3. After the code release passes CI, save these variables on
   `luxury-hotel-backend` and deploy:

   ```dotenv
   EMAIL_PROVIDER=brevo
   EMAIL_FROM_EMAIL=<verified Brevo sender>
   BREVO_API_KEY=<secret stored only in Render>
   ```

4. Keep `BACKEND_BASE_URL` and `FRONTEND_BASE_URL` pointing at the deployed
   backend and website. `HOTEL_EMAIL` controls Reply-To when present. No Brevo
   credentials belong in Vercel or `NEXT_PUBLIC_*` variables.
5. Check startup/health, send one authorized controlled email and inspect
   Brevo transactional logs plus actual receipt. HTTP 201 with a message ID
   means accepted by the provider; it does not establish inbox delivery.
6. Check verification/reset URLs point at the production origins. Do not
   generate real booking/payment mutations just to test email rendering.

HTTPS avoids Render Free's SMTP-port restrictions. The adapter has a 10-second
connection timeout and 20-second read timeout, follows no redirects and performs
no automatic resend/provider fallback inside the adapter. The durable outbox
uses the bounded retry policy below. HTTP errors or malformed acceptance receipts fail delivery;
verification-token state is restored on failure. A timeout can happen after
provider acceptance, so check provider logs before manually retrying a message.

Brevo Free is limited to 300 emails/day as documented on 2026-09-26. Monitor the
transactional quota, account activation and key expiry. A verified Gmail sender
does not authenticate a hotel-owned domain: check spam/rewritten sender behavior
and configure SPF/DKIM/DMARC when an owned domain is available.

## Rollback

Set `EMAIL_PROVIDER=sendgrid` only after supplying an active SendGrid key and
verified sender. `EMAIL_FROM_EMAIL` overrides the legacy sender, so align it or
remove it to use `VERIFICATION_SENDGRID_FROM_EMAIL`. The expired SendGrid trial
is not a functioning fallback. V40 is an additive email-outbox migration;
retain it on application rollback. Stop email workers before rolling back to
pre-V40 code, which does not understand booking outbox rows, receipt tracking
or the two new terminal states. Do not down-migrate or restore the database
as a routine application rollback. Reconcile outstanding messages before
restarting a compatible worker; do not reset statuses in bulk.

## Durable booking and audit delivery (V40)

- Booking confirmation snapshots are enqueued in the same transaction as the
  booking event. Rollback removes both; provider I/O happens after commit in
  the existing scheduled worker. One row per reservation prevents duplicate
  events from queuing multiple confirmations. Existing bookings are not backfilled.
- `audit_notification_outbox` now contains both audit alerts and booking emails.
  Frozen HTML/plain text, including capability links, is AES-GCM encrypted with
  the existing `REFUND_DATA_ENCRYPTION_KEY` and an email-specific payload prefix.
  Preserve that key across redeploys/backups; rotating it without re-encrypting
  pending snapshots puts them into review. Neither the snapshot nor token is logged.
- `FAILED` means scheduled retry; `PERMANENT_FAILURE` and `REVIEW_REQUIRED`
  never automatically resend. All three count in the admin monitoring failure total.
  HTTP 429 backs off (30 seconds, doubling, maximum 8 attempts); definite 4xx
  errors stop. A timeout/5xx/malformed receipt is uncertain acceptance.
- Uncertain sends only retry with the identical snapshot and provider key,
  within a conservative 10-minute window. Brevo's older announcement says
  15 minutes, while current batch-idempotency docs say 30; the shorter local
  window deliberately leaves a margin. This is bounded deduplication, not
  an exactly-once delivery guarantee. Provider/account-key changes also stop
  uncertain retries. Crash recovery defaults to two minutes; a longer configured
  timeout can send stale attempts straight to review.
- Persisted attempt numbers fence old workers. A DB error after provider
  acceptance leaves the row recoverable; it is never treated as a definite rejection.
  V40 moves legacy FAILED/PROCESSING rows (which had no idempotency key) to review.
- `SENT` and `provider_message_id` mean provider acceptance. They do not mean
  delivery. A duplicate-key response may have no original message ID; that fact
  is recorded. Verification and password-reset failures return their normal
  generic public success response while the token transaction rolls back.
  This removes the error-status account oracle; responses are not constant-time.

## Optional delivery/bounce webhook

1. Generate a separate random 32+ character secret after approval to create
   and distribute this credential; never reuse the Brevo API key. Set it in
   Render as `BREVO_WEBHOOK_SECRET` and in Brevo with authentication method
   **Token** (Authorization: Bearer). The API also accepts the custom HTTP header
   `X-Brevo-Webhook-Token`. Blank/short secrets keep the endpoint disabled (404).
2. Create an **individual-event transactional** webhook pointing to
   `https://luxury-hotel-backend-c06y.onrender.com/api/email/brevo/webhook`.
   Subscribe to sent, delivered, deferred, softBounce, hardBounce, blocked,
   invalid, spam and error. Do not select batched delivery.
3. The worker includes `X-Mailin-custom: hotel-email-key:<uuid>` to correlate
   callbacks even when they arrive before the HTTP send response is saved.
   Both recipient and key must match; unrelated account email is ignored.
   Only outbox booking/audit mail is tracked, not verification/reset/contact mail.
4. `delivery_status` and `delivery_event_at_utc` distinguish acceptance,
   delivery, deferral and failure. Duplicate/stale events cannot regress a
   delivered or terminal-failure result. Delivery failures count in monitoring.
   Webhooks change email evidence only, never reservation/payment state.
5. Test missing/wrong authentication and a synthetic callback in isolated QA.
   A real delivery/bounce test still needs a controlled recipient and permission.

## Operator reconciliation

Use an authorized read-only database session to inspect metadata (never select
`encrypted_message` or `payload_json` into a report):

```sql
SELECT id, reservation_id, notification_type, status, attempts,
       first_attempt_at_utc, next_attempt_at_utc, provider_message_id,
       delivery_status, delivery_event_at_utc, last_error
FROM audit_notification_outbox
WHERE status IN ('FAILED', 'PERMANENT_FAILURE', 'REVIEW_REQUIRED')
   OR delivery_status IN ('HARD_BOUNCE', 'BLOCKED', 'INVALID_EMAIL', 'SPAM', 'ERROR')
ORDER BY created_at_utc;
```

For permanent rejection, repair sender/credentials/recipient first. For uncertain
acceptance, inspect Brevo logs using the message ID or recipient/time in a private
session and obtain confirmation before any manual resend. Do not blindly change
REVIEW_REQUIRED to PENDING: the provider may already have delivered the original.
No automatic manual-retry endpoint is exposed. Keep the existing Free quotas;
this change does not enable new paid services or keepalive monitors.

## References

- [Brevo transactional API](https://developers.brevo.com/docs/send-a-transactional-email)
- [Idempotency payload and current window](https://developers.brevo.com/docs/heterogenous-versions-batch-emails)
- [Original idempotency announcement](https://developers.brevo.com/changelog/2021/11/10)
- [Transactional webhook payloads](https://developers.brevo.com/docs/transactional-webhooks)
- [Webhook authentication](https://developers.brevo.com/docs/secured-webhooks)
- [Brevo Free limits](https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan)
- [Render Free limits](https://render.com/docs/free)
