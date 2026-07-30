const CATALOG_TTL_MS = 30_000;
const catalogCache = new Map<string, { expiresAt: number; data: unknown[] }>();
const catalogRequestsInFlight = new Map<string, Promise<unknown[]>>();

const getPublicList = async <T>(url: string): Promise<T[]> => {
  const cached = catalogCache.get(url);
  if (cached && cached.expiresAt > Date.now()) return cached.data as T[];

  const currentRequest = catalogRequestsInFlight.get(url);
  if (currentRequest) return currentRequest as Promise<T[]>;

  const request = fetch(url, { headers: { Accept: "application/json" } })
    .then(async (response) => {
      if (!response.ok) throw new Error(`Public catalog request failed with status ${response.status}`);
      const payload = await response.json() as { data?: unknown };
      const data = Array.isArray(payload.data) ? payload.data : [];
      catalogCache.set(url, { data, expiresAt: Date.now() + CATALOG_TTL_MS });
      return data;
    })
    .finally(() => catalogRequestsInFlight.delete(url));

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
