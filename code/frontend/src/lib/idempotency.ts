const PREFIX = "hotel:idempotency:";

function newKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Idempotency keys are operational, non-secret values. sessionStorage keeps a
 * retry in the same tab stable without pretending to be durable identity
 * storage across closed tabs or new browser contexts.
 */
export function getOrCreateIdempotencyKey(operationScope: string): string {
  if (typeof window === "undefined") return newKey();
  const storageKey = `${PREFIX}${operationScope}`;
  const existing = window.sessionStorage.getItem(storageKey);
  if (existing) return existing;
  const created = newKey();
  window.sessionStorage.setItem(storageKey, created);
  return created;
}

export function clearIdempotencyKey(operationScope: string): void {
  if (typeof window === "undefined") return;
  window.sessionStorage.removeItem(`${PREFIX}${operationScope}`);
}
