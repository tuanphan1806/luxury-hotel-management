import type { NextApiRequest, NextApiResponse } from "next";

const origin = (process.env.BACKEND_INTERNAL_URL || process.env.NEXT_PUBLIC_BACKEND_URL || "http://localhost:8080").replace(/\/+$/, "");

export default async function roomTypes(req: NextApiRequest, res: NextApiResponse) {
  const parts = req.query.parts;
  const path = Array.isArray(parts) ? parts.join("/") : parts ?? "";
  const method = req.method;
  const reading = method === "GET" || method === "HEAD";
  const valid = reading ? /^(?:admin|[1-9]\d*)?$/.test(path)
    : method === "POST" ? path === ""
    : method === "PATCH" ? /^[1-9]\d*\/active$/.test(path)
    : (method === "PUT" || method === "DELETE") && /^[1-9]\d*$/.test(path);
  res.setHeader("Cache-Control", "no-store");
  if (!valid) return res.status(404).json({ message: "Not found" });

  const headers = new Headers({ Accept: "application/json" });
  if (req.headers.authorization) headers.set("Authorization", req.headers.authorization);
  if (!reading && !req.headers.authorization) return res.status(401).json({ message: "Unauthorized" });
  const correlationId = req.headers["x-correlation-id"];
  if (typeof correlationId === "string") headers.set("X-Correlation-ID", correlationId);
  const url = new URL(`${origin}/api/room-types${path ? `/${path}` : ""}`);
  for (const [key, value] of Object.entries(req.query)) {
    if (key === "parts" || value === undefined) continue;
    for (const item of Array.isArray(value) ? value : [value]) url.searchParams.append(key, item);
  }
  const hasBody = method === "POST" || method === "PUT" || method === "PATCH";
  if (hasBody) headers.set("Content-Type", "application/json");
  let upstream: Response;
  let body: string;
  try {
    upstream = await fetch(url, { method, headers, redirect: "manual", body: hasBody ? JSON.stringify(req.body) : undefined });
    body = await upstream.text();
  } catch {
    return res.status(502).json({ message: "Room service unavailable" });
  }
  if (upstream.ok && !reading) {
    try {
      if (JSON.parse(body).success !== true) throw new Error();
    } catch {
      return res.status(502).json({ message: "Room update was not confirmed" });
    }
    // Eager ISR builds the replacement while Spring is awake. The previous
    // HTML remains available to guests if a refresh fails after the commit.
    const refreshes = await Promise.allSettled([res.revalidate("/rooms"), res.revalidate("/")]);
    if (refreshes.some((result) => result.status === "rejected")) {
      res.setHeader("X-Room-Catalog-Refresh", "pending");
      console.error("Room catalog refresh incomplete after a successful room type update");
    }
  }
  for (const name of ["Content-Type", "Retry-After", "WWW-Authenticate", "X-Correlation-ID"]) {
    const value = upstream.headers.get(name);
    if (value) res.setHeader(name, value);
  }
  res.status(upstream.status).end(reading && method === "HEAD" ? undefined : body);
}
