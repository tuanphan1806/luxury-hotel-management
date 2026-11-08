const RECOVERY_WINDOW_MS = 210_000;
const REQUEST_TIMEOUT_MS = 12_000;
const IDLE_WINDOW_MS = 15 * 60_000;
const RETRYABLE_STATUSES = new Set([408, 425, 429, 500, 502, 503, 504]);

type Visibility = Pick<Document, "visibilityState" | "addEventListener" | "removeEventListener">;

/**
 * Cached pages may never request a live API. Wake the backend on a visible
 * visit, independently of catalog caches. Stop after readiness succeeds:
 * this is not a keep-awake monitor, and the readiness probe never queries DB.
 */
export function watchBackendWakeup(visibility: Visibility): () => void {
  let disposed = false;
  let deadline = 0;
  let lastRequestAt = -Infinity;
  let nextRequestAt = 0;
  let attempt = 0;
  let completed = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let activeRequest: AbortController | undefined;

  function pause() {
    clearTimeout(timer);
    timer = undefined;
    const request = activeRequest;
    activeRequest = undefined;
    request?.abort();
  }

  function schedule() {
    if (disposed || visibility.visibilityState !== "visible" || completed || activeRequest) return;
    clearTimeout(timer);
    const now = Date.now();
    if (Math.max(now, nextRequestAt) >= deadline) {
      completed = true;
      return;
    }
    // Deferring also cancels React Strict Mode's discarded first mount before
    // it can issue a duplicate request.
    timer = setTimeout(() => { void probe(); }, Math.max(0, nextRequestAt - now));
  }

  async function probe() {
    timer = undefined;
    if (disposed || visibility.visibilityState !== "visible" || Date.now() >= deadline) return;
    const controller = new AbortController();
    activeRequest = controller;
    lastRequestAt = Date.now();
    const timeout = setTimeout(() => controller.abort(), Math.min(REQUEST_TIMEOUT_MS, deadline - Date.now()));
    let ready = false;
    let permanentFailure = false;
    let retryAfterMs = 0;

    try {
      const response = await fetch("/backend_proxy/actuator/health/readiness", {
        method: "GET",
        cache: "no-store",
        credentials: "omit",
        headers: { Accept: "application/json" },
        signal: controller.signal,
      });
      if (response.ok) {
        const body: unknown = await response.json();
        ready = typeof body === "object" && body !== null && "status" in body && body.status === "UP";
      } else {
        permanentFailure = !RETRYABLE_STATUSES.has(response.status);
        const header = response.headers.get("Retry-After");
        if (header) {
          const parsed = /^\d+$/.test(header) ? Number(header) * 1_000 : Date.parse(header) - Date.now();
          if (Number.isFinite(parsed)) retryAfterMs = Math.max(0, parsed);
        }
      }
    } catch {
      // Network errors, aborted cold requests and a platform's HTML loading
      // page are not readiness. Retry only this read, within the same budget.
    } finally {
      clearTimeout(timeout);
    }

    // A hidden/unmounted tab can abort this attempt while another one starts.
    if (activeRequest !== controller || disposed) return;
    activeRequest = undefined;
    if (ready || permanentFailure) {
      completed = true;
      return;
    }
    attempt += 1;
    nextRequestAt = Date.now() + Math.max(Math.min(attempt * 5_000, 20_000), retryAfterMs);
    schedule();
  }

  function onVisibilityChange() {
    if (visibility.visibilityState !== "visible") {
      pause();
      return;
    }
    const now = Date.now();
    if (!deadline || now - lastRequestAt >= IDLE_WINDOW_MS) {
      deadline = now + RECOVERY_WINDOW_MS;
      nextRequestAt = now;
      attempt = 0;
      completed = false;
    }
    schedule();
  }

  visibility.addEventListener("visibilitychange", onVisibilityChange);
  onVisibilityChange();
  return () => {
    disposed = true;
    visibility.removeEventListener("visibilitychange", onVisibilityChange);
    pause();
  };
}
