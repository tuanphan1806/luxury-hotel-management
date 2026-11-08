const CATALOG_TTL_MS = 30_000;
// A Free backend can take over two minutes to wake. Keep readers pending long
// enough to recover, without polling forever or retrying any write operation.
const CATALOG_RECOVERY_WINDOW_MS = 210_000;
const CATALOG_REQUEST_TIMEOUT_MS = 15_000;
const RETRYABLE_STATUSES = new Set([408, 429, 502, 503, 504]);
const catalogCache = new Map<string, { expiresAt: number; data: unknown[] }>();
const catalogRequestsInFlight = new Map<string, Promise<unknown[]>>();
const pendingRequests = new Set<string>();
const failedRequests = new Set<string>();
const listeners = new Set<() => void>();
type CatalogConnectionState = "idle" | "loading" | "unavailable";
let connectionState: CatalogConnectionState = "idle";

export const getCatalogConnectionState = () => connectionState;
export const getServerCatalogConnectionState = (): CatalogConnectionState => "idle";
export const subscribeCatalogConnection = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};

function publishConnectionState() {
  const next = failedRequests.size ? "unavailable" : pendingRequests.size ? "loading" : "idle";
  if (next === connectionState) return;
  connectionState = next;
  listeners.forEach((listener) => listener());
}

async function fetchCatalog(url: string): Promise<unknown[]> {
  // Only these read-only catalog requests are retried. Never retry a booking
  // or payment mutation when the server's response is uncertain.
  const deadline = Date.now() + CATALOG_RECOVERY_WINDOW_MS;
  for (let attempt = 0; ; attempt += 1) {
    let failure: unknown;
    let retryAfterMs = 0;
    try {
      const response = await fetch(url, {
        headers: { Accept: "application/json" },
        signal: AbortSignal.timeout(Math.max(1, Math.min(CATALOG_REQUEST_TIMEOUT_MS, deadline - Date.now()))),
      });
      if (!response.ok) {
        failure = new Error(`Public catalog request failed with status ${response.status}`);
        if (!RETRYABLE_STATUSES.has(response.status)) throw failure;
        const retryAfter = response.headers?.get("Retry-After");
        if (retryAfter) {
          retryAfterMs = /^\d+$/.test(retryAfter)
            ? Number(retryAfter) * 1_000
            : Math.max(0, Date.parse(retryAfter) - Date.now());
          if (!Number.isFinite(retryAfterMs)) retryAfterMs = 0;
        }
      } else {
        const payload = await response.json() as { data?: unknown };
        if (!Array.isArray(payload?.data)) throw new Error("Invalid public catalog response");
        return payload.data;
      }
    } catch (error) {
      const transient = error instanceof TypeError || (error instanceof DOMException
        && ["TimeoutError", "AbortError"].includes(error.name));
      if (!transient) throw error;
      failure = error;
    }

    const delayMs = Math.max(Math.min((attempt + 1) * 5_000, 20_000), retryAfterMs);
    if (Date.now() + delayMs >= deadline) throw failure;
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    // A background tab may resume long after its timer was scheduled.
    if (Date.now() >= deadline) throw failure;
  }
}

const getPublicList = async <T>(url: string): Promise<T[]> => {
  const cached = catalogCache.get(url);
  if (cached && cached.expiresAt > Date.now()) return cached.data as T[];

  const currentRequest = catalogRequestsInFlight.get(url);
  if (currentRequest) return currentRequest as Promise<T[]>;

  pendingRequests.add(url);
  failedRequests.delete(url);
  publishConnectionState();
  const request = fetchCatalog(url)
    .then((data) => {
      catalogCache.set(url, { data, expiresAt: Date.now() + CATALOG_TTL_MS });
      return data;
    })
    .catch((error: unknown) => {
      failedRequests.add(url);
      throw error;
    })
    .finally(() => {
      catalogRequestsInFlight.delete(url);
      pendingRequests.delete(url);
      publishConnectionState();
    });

  catalogRequestsInFlight.set(url, request);
  return request as Promise<T[]>;
};

/**
 * Chia sẻ cùng một request RoomType giữa các component/page public.
 * Việc giữ promise đang chạy cũng chặn request kép do React Strict Mode ở dev.
 */
export const getPublicRoomTypes = async <T>(): Promise<T[]> => {
  return getPublicList<T>("/catalog_proxy/room-types");
};

export const getPublicFacilities = <T>() => getPublicList<T>("/catalog_proxy/facilities");

export const getPublicGalleries = <T>() => getPublicList<T>("/catalog_proxy/galleries");
