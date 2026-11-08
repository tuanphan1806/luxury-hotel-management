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

  it("recovers after a 140-second cold start without requiring a page reload", async () => {
    vi.useFakeTimers();
    const startedAt = Date.now();
    const fetchMock = vi.fn().mockImplementation(async () => Date.now() - startedAt < 140_000
      ? { ok: false, status: 503 }
      : { ok: true, json: async () => ({ data: [{ id: 6 }] }) });
    vi.stubGlobal("fetch", fetchMock);
    const catalog = await import("./public-catalog");
    const first = catalog.getPublicRoomTypes();
    const concurrent = catalog.getPublicRoomTypes();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(catalog.getCatalogConnectionState()).toBe("loading");
    await vi.advanceTimersByTimeAsync(100_000);
    expect(await first).toEqual([{ id: 6 }]);
    expect(await concurrent).toEqual([{ id: 6 }]);
    expect(catalog.getCatalogConnectionState()).toBe("idle");
    expect(fetchMock.mock.calls.length).toBeLessThan(12);
  });

  it("bounds a lasting outage and permits a fresh manual request after recovery", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 504 });
    vi.stubGlobal("fetch", fetchMock);
    const catalog = await import("./public-catalog");
    const result = expect(catalog.getPublicFacilities()).rejects.toThrow("status 504");
    await vi.advanceTimersByTimeAsync(210_000);
    await result;
    expect(catalog.getCatalogConnectionState()).toBe("unavailable");
    const attempts = fetchMock.mock.calls.length;
    expect(attempts).toBeLessThanOrEqual(13);
    await vi.advanceTimersByTimeAsync(600_000);
    expect(fetchMock).toHaveBeenCalledTimes(attempts);
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ data: [] }) });
    expect(await catalog.getPublicFacilities()).toEqual([]);
    expect(catalog.getCatalogConnectionState()).toBe("idle");
  });

  it.each(["45", "Sun, 08 Nov 2026 14:00:45 GMT"])("respects Retry-After %s on rate limiting", async (header) => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-08T14:00:00Z"));
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 429, headers: new Headers({ "Retry-After": header }) })
      .mockResolvedValue({ ok: true, json: async () => ({ data: [] }) });
    vi.stubGlobal("fetch", fetchMock);
    const catalog = await import("./public-catalog");
    const result = catalog.getPublicFacilities();
    await vi.advanceTimersByTimeAsync(44_999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(await result).toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("does not retry before a Retry-After that exceeds the recovery budget", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false, status: 429, headers: new Headers({ "Retry-After": "300" }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const catalog = await import("./public-catalog");
    await expect(catalog.getPublicFacilities()).rejects.toThrow("status 429");
    await vi.advanceTimersByTimeAsync(600_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each([new TypeError("Failed to fetch"), new DOMException("Timed out", "TimeoutError")])(
    "recovers a network failure without caching an empty catalog: %s", async (failure) => {
      vi.useFakeTimers();
      const fetchMock = vi.fn().mockRejectedValueOnce(failure)
        .mockResolvedValue({ ok: true, json: async () => ({ data: [{ id: 1 }] }) });
      vi.stubGlobal("fetch", fetchMock);
      const catalog = await import("./public-catalog");
      const result = catalog.getPublicRoomTypes();
      await vi.advanceTimersByTimeAsync(5_000);
      expect(await result).toEqual([{ id: 1 }]);
      expect(catalog.getCatalogConnectionState()).toBe("idle");
    },
  );

  it("stops a pending retry when a background tab resumes after the deadline", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 503 });
    vi.stubGlobal("fetch", fetchMock);
    const catalog = await import("./public-catalog");
    const result = expect(catalog.getPublicRoomTypes()).rejects.toThrow("status 503");
    await vi.advanceTimersByTimeAsync(0);
    vi.setSystemTime(Date.now() + 300_000);
    await vi.advanceTimersByTimeAsync(5_000);
    await result;
    expect(fetchMock).toHaveBeenCalledTimes(1);
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
