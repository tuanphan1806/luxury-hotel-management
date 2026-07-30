import { NextResponse } from "next/server";

const catalogResources = {
  "room-types": "/api/room-types",
  facilities: "/api/facilities",
  galleries: "/api/galleries",
} as const;

const backendOrigin = (
  process.env.BACKEND_INTERNAL_URL
  || process.env.NEXT_PUBLIC_BACKEND_URL
  || "http://localhost:8080"
).replace(/\/+$/, "");

export const revalidate = 60;

export async function GET(
  _request: Request,
  context: { params: Promise<{ resource: string }> },
) {
  const { resource } = await context.params;
  const endpoint = catalogResources[resource as keyof typeof catalogResources];
  if (!endpoint) return NextResponse.json({ message: "Not found" }, { status: 404 });

  try {
    const upstream = await fetch(`${backendOrigin}${endpoint}`, {
      headers: { Accept: "application/json" },
      next: { revalidate: 60 },
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
      },
    });
  } catch {
    return NextResponse.json(
      { message: "Public catalog is temporarily unavailable" },
      { status: 503, headers: { "Cache-Control": "no-store" } },
    );
  }
}
