import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("public catalog cache", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
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
      { headers: { Accept: "application/json" } },
    );
    expect(first).toEqual([{ id: 1, typeName: "Standard" }]);
    expect(second).toEqual(first);
    expect(third).toEqual(first);
  });

  it("does not cache an upstream error", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 503 });
    vi.stubGlobal("fetch", fetchMock);
    const { getPublicFacilities } = await import("./public-catalog");

    await expect(getPublicFacilities()).rejects.toThrow("status 503");
    await expect(getPublicFacilities()).rejects.toThrow("status 503");

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
