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
no automatic resend/provider fallback. Existing business outbox retry policies
remain in force. HTTP errors or malformed acceptance receipts fail delivery;
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
is not a functioning fallback. Do not restore the database for this adapter
change; it introduces no migration or data rewrite.

## References

- [Brevo transactional API](https://developers.brevo.com/docs/send-a-transactional-email)
- [Brevo Free limits](https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan)
- [Render Free limits](https://render.com/docs/free)
