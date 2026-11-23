// Each confirmed wake-up releases only readers that were already pending.
export const BACKEND_RECOVERY_WINDOW_MS = 210_000;
let nextReady = new AbortController();

export const nextBackendReadySignal = () => nextReady.signal;

export function retryAfterMs(header: unknown) {
  if (typeof header !== "string" || !header.trim()) return 0;
  const value = header.trim();
  if (/^\d+$/.test(value)) return Number(value) * 1000;
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - Date.now()) : 0;
}

export function notifyBackendReady() {
  const waiting = nextReady;
  nextReady = new AbortController();
  waiting.abort();
}

export function waitForBackendReadyOrTimeout(signal: AbortSignal, delayMs: number) {
  if (signal.aborted || delayMs <= 0) return Promise.resolve();
  return new Promise<void>((resolve) => {
    const finish = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    const timer = setTimeout(finish, delayMs);
    signal.addEventListener("abort", finish, { once: true });
  });
}
