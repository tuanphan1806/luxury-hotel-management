import { NextResponse } from "next/server";
import { catalogOrigin, readPublicRoomCatalog, RoomCatalogError } from "@/lib/room-catalog-server";

const catalogResources = {
  "room-types": "/api/room-types",
  facilities: "/api/facilities",
  galleries: "/api/galleries",
} as const;

export const revalidate = 60;

export async function GET(
  _request: Request,
  context: { params: Promise<{ resource: string }> },
) {
  const { resource } = await context.params;
  const endpoint = catalogResources[resource as keyof typeof catalogResources];
  if (!endpoint) return NextResponse.json({ message: "Not found" }, { status: 404 });

  try {
    if (resource === "room-types") {
      const { payload, readAt } = await readPublicRoomCatalog();
      // Only the descriptive HTML has a long lifetime. Suppress old published
      // rates while the short-lived data cache refreshes in the background.
      if (Date.now() - readAt > 360_000) throw new RoomCatalogError(503, "5");
      return NextResponse.json(payload, { headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
    }
    const upstream = await fetch(`${catalogOrigin}${endpoint}`, {
      headers: { Accept: "application/json" },
      next: { revalidate: 60 },
      signal: AbortSignal.timeout(12_000),
    });
    const body = await upstream.text();

    return new Response(body, {
      status: upstream.status,
      headers: {
        "Content-Type": upstream.headers.get("content-type") || "application/json; charset=utf-8",
        "Cache-Control": upstream.ok
          ? "public, max-age=30, s-maxage=60, stale-while-revalidate=300"
          : "no-store",
        "X-Content-Type-Options": "nosniff",
        ...(upstream.headers.has("Retry-After") ? { "Retry-After": upstream.headers.get("Retry-After")! } : {}),
      },
    });
  } catch (error) {
    const status = error instanceof RoomCatalogError ? error.status : 503;
    const retryAfter = error instanceof RoomCatalogError ? error.retryAfter : "5";
    return NextResponse.json(
      { message: "Public catalog is temporarily unavailable" },
      { status, headers: { "Cache-Control": "no-store", ...(retryAfter ? { "Retry-After": retryAfter } : {}) } },
    );
  }
}
