import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("public catalog cache", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("deduplicates concurrent calls and reuses the short browser cache", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ data: [{ id: 1, typeName: "Standard" }] }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const { getPublicRoomTypes } = await import("./public-catalog");

    const [first, second] = await Promise.all([
      getPublicRoomTypes<{ id: number }>(),
      getPublicRoomTypes<{ id: number }>(),
    ]);
    const third = await getPublicRoomTypes<{ id: number }>();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      "/catalog_proxy/room-types",
      { headers: { Accept: "application/json" }, signal: expect.any(AbortSignal) },
    );
    expect(first).toEqual([{ id: 1, typeName: "Standard" }]);
    expect(second).toEqual(first);
    expect(third).toEqual(first);
  });

  it("does not cache an upstream error", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 400 });
    vi.stubGlobal("fetch", fetchMock);
    const { getPublicFacilities } = await import("./public-catalog");

    await expect(getPublicFacilities()).rejects.toThrow("status 400");
    await expect(getPublicFacilities()).rejects.toThrow("status 400");

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("retries a transient failure once for concurrent readers and clears the connection notice", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 503 })
      .mockResolvedValue({ ok: true, json: async () => ({ data: [{ id: 1 }] }) });
    vi.stubGlobal("fetch", fetchMock);
    const catalog = await import("./public-catalog");
    const first = catalog.getPublicRoomTypes();
    const second = catalog.getPublicRoomTypes();
    expect(catalog.getCatalogConnectionState()).toBe("loading");
    await vi.advanceTimersByTimeAsync(5_000);
    expect(await first).toEqual([{ id: 1 }]);
    expect(await second).toEqual([{ id: 1 }]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(catalog.getCatalogConnectionState()).toBe("idle");
  });

  it("stops retrying after three failed reads and reports unavailable", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 504 });
    vi.stubGlobal("fetch", fetchMock);
    const catalog = await import("./public-catalog");
    const result = expect(catalog.getPublicFacilities()).rejects.toThrow("status 504");
    await vi.advanceTimersByTimeAsync(10_000);
    await result;
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(catalog.getCatalogConnectionState()).toBe("unavailable");
  });

  it("never treats a malformed successful response as an empty catalog", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ error: "not a catalog" }) });
    vi.stubGlobal("fetch", fetchMock);
    const { getPublicRoomTypes } = await import("./public-catalog");
    await expect(getPublicRoomTypes()).rejects.toThrow("Invalid public catalog response");
    await expect(getPublicRoomTypes()).rejects.toThrow("Invalid public catalog response");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
