const CATALOG_TTL_MS = 30_000;
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
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const response = await fetch(url, {
        headers: { Accept: "application/json" },
        signal: AbortSignal.timeout(15_000),
      });
      if (!response.ok) {
        if (attempt < 2 && [408, 502, 503, 504].includes(response.status)) {
          await new Promise((resolve) => setTimeout(resolve, 5_000));
          continue;
        }
        throw new Error(`Public catalog request failed with status ${response.status}`);
      }
      const payload = await response.json() as { data?: unknown };
      if (!Array.isArray(payload?.data)) throw new Error("Invalid public catalog response");
      return payload.data;
    } catch (error) {
      const transient = error instanceof TypeError || (error instanceof DOMException
        && ["TimeoutError", "AbortError"].includes(error.name));
      if (attempt === 2 || !transient) throw error;
      await new Promise((resolve) => setTimeout(resolve, 5_000));
    }
  }
  throw new Error("Public catalog is temporarily unavailable");
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
