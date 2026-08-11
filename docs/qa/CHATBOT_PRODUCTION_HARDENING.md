# Chatbot production hardening

## Boundary

The chatbot is a public assistant, not an alternative reservation or payment
workflow. Deterministic intent parsing, availability, capacity and Pricing V2
validation remain authoritative. The optional Gemini semantic fallback may only:

1. classify an ambiguous message as booking, clarify, or not booking;
2. ask one bounded clarification question when two booking interpretations remain;
3. hand the original, unmodified customer message to the deterministic parser.

The provider is not allowed to extract or rewrite dates, times, room names,
quantities or guest counts. Unknown JSON fields are rejected. Missing booking
facts remain missing and the deterministic flow asks the guest instead of
guessing them.

It cannot create, cancel or change a reservation, collect payment, issue a
refund, invent a price, or bypass the canonical `/booking` page.

## Browser privacy

- The widget keeps at most 30 messages for 30 minutes in `sessionStorage`.
- Email, phone, booking code, identity document, account/card-like numbers,
  labelled names and addresses are redacted before persistence.
- Only the internal `/my-bookings` link is accepted for a stored link action.
- Guests can clear the conversation from the widget header.
- The live in-memory conversation is still sent to the hotel API so a follow-up
  can be understood; provider prompts are separately PII-redacted by the backend.

## Response contract

- Gemini must return plain text for FAQ answers.
- Common Markdown is normalized to readable plain text as a defence in depth.
- Non-`STOP` provider responses are rejected so partial answers are not shown.
- Long answers are cut at a nearby sentence boundary, otherwise at a word
  boundary with an ellipsis.

## Metrics

The ADMIN-only Actuator metrics endpoint exposes low-cardinality counters/timers:

- `hotel.chat.requests` (`locale`)
- `hotel.chat.responses` (`action`, including `answer_only` for plain answers)
- `hotel.chat.request.duration` (`locale`, `outcome`)
- `hotel.chat.provider.calls` (`provider=gemini`, `status`)
- `hotel.chat.provider.duration` (`provider=gemini`, `status`)

Production provider configuration is managed outside Git. Render must have a
non-empty `GEMINI_API_KEY`; `GEMINI_API_MAX_CONCURRENT_REQUESTS` bounds outbound
provider calls and defaults to `4`. The chatbot remains functional with
deterministic answers when the provider key is absent or unavailable.

No question, conversation ID, IP, account, reservation or other personal value
is used as a metric tag. Gemini token counts and finish reason are DEBUG logs
only; prompts and answers are never logged.

## Verification

Backend curated eval and chatbot regression:

```powershell
.\mvnw.cmd "-Dtest=ChatBotServiceTest,ChatInputPolicyTest,GoogleGeminiChatClientTest,ChatResponsePolicyTest,ChatPrivacyRedactorTest,ChatIntentClassifierTest,ChatSemanticBookingFallbackTest,ChatbotConversationEvalTest,PublicEndpointRateLimitTest" test
```

Frontend privacy/session unit tests and desktop/mobile journey:

```powershell
pnpm.cmd run test:unit
.\node_modules\.bin\playwright.cmd test tests/e2e/chatbot-runtime.spec.ts
```

The same isolated journey is a required GitHub quality gate through
`pnpm run test:e2e:chatbot`; failure diagnostics are retained as a short-lived
workflow artifact.

The E2E journey runs on desktop and mobile and covers contextual handoff,
redacted persistence, explicit conversation clearing, and clearing while an
older request is still in flight. The older response must never enter the new
conversation or unlock its loading state.

The semantic fallback is controlled by
`CHATBOT_SEMANTIC_BOOKING_FALLBACK_ENABLED`. Turning it off restores the fully
deterministic path without changing the API contract.

## Deployment limits

- The current rate limiter and Gemini circuit breaker are process-local. This
  is correct for the current single Render instance; move their state to a
  shared store before running more than one backend instance.
- A real-provider smoke test, alert/dashboard verification and curated answer
  review remain deployment/UAT gates because unit and E2E tests intentionally
  do not spend Gemini quota or depend on an external provider.
