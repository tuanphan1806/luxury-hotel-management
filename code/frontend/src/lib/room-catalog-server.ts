import { unstable_cache } from "next/cache";
import { PHASE_PRODUCTION_BUILD } from "next/constants";
import { projectRoomPreviews } from "./room-preview";

const configuredOrigin = process.env.BACKEND_INTERNAL_URL || process.env.NEXT_PUBLIC_BACKEND_URL;
export const catalogOrigin = (configuredOrigin || "http://localhost:8080").replace(/\/+$/, "");
export class RoomCatalogError extends Error {
  constructor(readonly status: number, readonly retryAfter: string | null) {
    super(`Room catalog returned ${status}`);
  }
}

async function fetchRoomCatalog() {
  const response = await fetch(`${catalogOrigin}/api/room-types`, {
    headers: { Accept: "application/json" }, cache: "no-store", signal: AbortSignal.timeout(12_000),
  });
  if (!response.ok) throw new RoomCatalogError(response.status, response.headers.get("Retry-After"));
  const payload = await response.json();
  projectRoomPreviews(payload);
  return {
    readAt: Date.now(),
    payload: { ...payload, data: payload.data.filter((room: { active?: boolean }) => room.active !== false) },
  };
}

// Next can start revalidation before returning an older entry to the route.
// Readers that need fresh rates can join that origin request until it settles.
let pendingOriginRead: ReturnType<typeof fetchRoomCatalog> | undefined;
function refreshRoomCatalog() {
  pendingOriginRead ??= fetchRoomCatalog().finally(() => { pendingOriginRead = undefined; });
  return pendingOriginRead;
}

const readCachedRoomCatalog = unstable_cache(refreshRoomCatalog, ["public-room-list-v1", catalogOrigin], { revalidate: 60 });
const MAX_RATE_AGE_MS = 6 * 60_000;

export async function readPublicRoomCatalog() {
  const cached = await readCachedRoomCatalog();
  if (Date.now() - cached.readAt <= MAX_RATE_AGE_MS) return cached.payload;
  return (await refreshRoomCatalog()).payload;
}

const readRoomPreviews = unstable_cache(async () => {
  // The first production build can wake Render before publishing the new HTML.
  // Runtime refreshes are short. An exception preserves the last successful ISR page.
  const coldBuild = Boolean(process.env.VERCEL) && process.env.NEXT_PHASE === PHASE_PRODUCTION_BUILD;
  const budget = coldBuild ? 210_000 : 12_000;
  const deadline = Date.now() + budget;
  for (;;) {
    try {
      return projectRoomPreviews((await readCachedRoomCatalog()).payload);
    } catch (error) {
      if (!coldBuild) throw error;
      let delay = 5_000;
      if (error instanceof RoomCatalogError) {
        if (![408, 425, 429, 500, 502, 503, 504].includes(error.status)) throw error;
        if (error.retryAfter) {
          const minimum = /^\d+$/.test(error.retryAfter) ? Number(error.retryAfter) * 1000 : Date.parse(error.retryAfter) - Date.now();
          if (Number.isFinite(minimum)) delay = Math.max(delay, minimum);
        }
      }
      if (Date.now() + delay + 12_000 >= deadline) throw error;
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
  }
}, ["room-descriptions-v1", catalogOrigin], { revalidate: 3600 });

export async function getRoomPreviews() {
  try {
    return await readRoomPreviews();
  } catch (error) {
    if (configuredOrigin || process.env.VERCEL) throw error;
    // Local frontend-only builds can start without a database. null means unknown.
    return null;
  }
}
