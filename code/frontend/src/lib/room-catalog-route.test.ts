import { afterEach, describe, expect, it, vi } from "vitest";
import { GET } from "../app/catalog_proxy/[resource]/route";

const cachedRead = vi.hoisted(() => vi.fn());
vi.mock("next/cache", () => ({
  unstable_cache: (load: () => Promise<unknown>, keys: string[]) =>
    keys[0] === "public-room-list-v1" ? () => cachedRead(load) : load,
}));

const storedPayload = { success: true, data: [{ id: 1, typeName: "Standard", basePrice: 100 }] };
function stored(minutes: number) {
  return { readAt: Date.now() - minutes * 60_000, payload: storedPayload };
}
function getRooms() {
  return GET(new Request("http://localhost/catalog_proxy/room-types"), {
    params: Promise.resolve({ resource: "room-types" }),
  });
}

afterEach(() => {
  cachedRead.mockReset();
  vi.unstubAllGlobals();
});

describe("public room catalog route", () => {
  it("serves a recent snapshot without another origin request", async () => {
    cachedRead.mockResolvedValue(stored(2));
    const origin = vi.fn();
    vi.stubGlobal("fetch", origin);
    const response = await getRooms();
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(storedPayload);
    expect(origin).not.toHaveBeenCalled();
  });

  it("waits for fresh rates and shares the pending background read on an expired hit", async () => {
    let finish!: (response: Response) => void;
    const origin = vi.fn(() => new Promise<Response>((resolve) => { finish = resolve; }));
    vi.stubGlobal("fetch", origin);
    cachedRead.mockImplementation((refresh: () => Promise<unknown>) => {
      void refresh();
      return stored(7);
    });
    const requests = Promise.all([getRooms(), getRooms()]);
    await vi.waitFor(() => expect(origin).toHaveBeenCalled(), { interval: 1 });
    const current = { id: 1, typeName: "Standard", basePrice: 150 };
    finish(Response.json({ success: true, data: [current, { id: 2, active: false }] }));
    for (const response of await requests) {
      expect(response.status).toBe(200);
      expect(response.headers.get("Cache-Control")).toBe("no-store");
      expect((await response.json()).data).toEqual([current]);
    }
    expect(origin).toHaveBeenCalledTimes(1);
  });

  it("keeps real errors and Retry-After, then accepts an empty live catalog on recovery", async () => {
    cachedRead.mockResolvedValue(stored(7));
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 429, headers: { "Retry-After": "9" } }))
      .mockResolvedValueOnce(new Response("<html>Starting</html>"))
      .mockResolvedValueOnce(Response.json({ success: true, data: [] })));
    const limited = await getRooms();
    expect(limited.status).toBe(429);
    expect(limited.headers.get("Retry-After")).toBe("9");
    expect(await limited.json()).not.toHaveProperty("data");
    const invalid = await getRooms();
    expect(invalid.status).toBe(503);
    expect(await invalid.json()).not.toHaveProperty("data");
    const recovered = await getRooms();
    expect(recovered.status).toBe(200);
    expect((await recovered.json()).data).toEqual([]);
  });
});
