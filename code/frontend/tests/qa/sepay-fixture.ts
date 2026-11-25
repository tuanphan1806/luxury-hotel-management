import { createHmac } from "node:crypto";
import { expect, type APIRequestContext } from "@playwright/test";
import { requireIsolation } from "./require-isolation";

const WEBHOOK_SECRET = process.env.QA_SEPAY_WEBHOOK_SECRET?.trim() ?? "";
const MERCHANT_ACCOUNT = process.env.QA_MERCHANT_BANK_ACCOUNT?.trim() ?? "";

function qaUrl(path: string) {
  requireIsolation();
  return process.env.E2E_QA_API + path;
}

export function sePayTransactionDate() {
  return new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).format(new Date()).replace("T", " ");
}

function signedWebhook(rawBody: string) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const digest = createHmac("sha256", WEBHOOK_SECRET)
    .update(`${timestamp}.${rawBody}`)
    .digest("hex");
  return {
    "Content-Type": "application/json",
    "X-SePay-Timestamp": timestamp,
    "X-SePay-Signature": `sha256=${digest}`,
  };
}

export async function deliverSignedWebhook(
  request: APIRequestContext,
  payload: Record<string, unknown>,
  rejectInvalidFirst = false,
) {
  const rawBody = JSON.stringify(payload);
  if (rejectInvalidFirst) {
    const rejected = await request.post(qaUrl("/api/payments/sepay/webhook"), {
      headers: {
        "Content-Type": "application/json",
        "X-SePay-Timestamp": Math.floor(Date.now() / 1000).toString(),
        "X-SePay-Signature": "sha256=invalid",
      },
      data: rawBody,
    });
    expect(rejected.status()).toBe(401);
  }

  const accepted = await request.post(qaUrl("/api/payments/sepay/webhook"), {
    headers: signedWebhook(rawBody),
    data: rawBody,
  });
  expect(accepted.status(), await accepted.text()).toBe(200);
  expect(await accepted.json()).toEqual({ success: true });

  const replay = await request.post(qaUrl("/api/payments/sepay/webhook"), {
    headers: signedWebhook(rawBody),
    data: rawBody,
  });
  expect(replay.status(), await replay.text()).toBe(200);
  expect(await replay.json()).toEqual({ success: true });
}

export async function settleThroughSignedWebhook(
  request: APIRequestContext,
  payment: {expectedAmount: number; transferContent: string; transactionReference: string},
  eventOffset: number,
  transferAmount = Number(payment.expectedAmount),
) {
  await deliverSignedWebhook(request, {
    id: Date.now() + eventOffset,
    gateway: "TPBank",
    transactionDate: sePayTransactionDate(),
    accountNumber: MERCHANT_ACCOUNT,
    code: null,
    content: payment.transferContent || payment.transactionReference,
    transferType: "in",
    transferAmount,
    accumulated: 0,
    subAccount: null,
    referenceCode: `QA-SEPAY-${Date.now()}-${eventOffset}`,
  }, true);
}
