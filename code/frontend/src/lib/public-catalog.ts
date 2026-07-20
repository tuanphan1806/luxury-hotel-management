import { cachedGet, invalidateGetCache } from "@/lib/api";

const CATALOG_TTL_MS = 30_000;

const getPublicList = async <T>(url: string): Promise<T[]> => {
  const response = await cachedGet(url, { ttlMs: CATALOG_TTL_MS });
  return Array.isArray(response.data?.data) ? response.data.data as T[] : [];
};

/**
 * Chia sẻ cùng một request RoomType giữa các component/page public.
 * Việc giữ promise đang chạy cũng chặn request kép do React Strict Mode ở dev.
 */
export const getPublicRoomTypes = async <T>(): Promise<T[]> => {
  return getPublicList<T>("/api/room-types");
};

export const invalidatePublicRoomTypes = () => {
  invalidateGetCache("/api/room-types");
};

export const getPublicFacilities = <T>() => getPublicList<T>("/api/facilities");

export const getPublicGalleries = <T>() => getPublicList<T>("/api/galleries");
