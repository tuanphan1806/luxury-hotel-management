import type { NextApiRequest, NextApiResponse } from "next";
import { afterEach, describe, expect, it, vi } from "vitest";
import roomTypes from "../pages/api/room-types/[[...parts]]";

afterEach(() => vi.unstubAllGlobals());

function publish(refresh = vi.fn().mockResolvedValue(undefined), authorization = "Bearer local-test") {
  const req = {
    method: "PATCH", query: { parts: ["7", "active"] }, headers: { authorization }, body: { active: false },
  } as unknown as NextApiRequest;
  const res = {
    status: vi.fn().mockReturnThis(), setHeader: vi.fn(), json: vi.fn(), end: vi.fn(), revalidate: refresh,
  };
  return roomTypes(req, res as unknown as NextApiResponse).then(() => res);
}

describe("publishing room changes", () => {
  it("refreshes both pages after Spring confirms the change", async () => {
    const send = vi.fn().mockResolvedValue(Response.json({ success: true }));
    vi.stubGlobal("fetch", send);
    const res = await publish();
    expect(send).toHaveBeenCalledTimes(1);
    expect(res.revalidate.mock.calls).toEqual([["/rooms"], ["/"]]);
    expect(res.status).toHaveBeenCalledWith(200);
  });

  it("rejects unauthenticated requests and does not refresh after an upstream denial", async () => {
    const send = vi.fn().mockResolvedValue(Response.json({ success: false }, { status: 403 }));
    vi.stubGlobal("fetch", send);
    const anonymous = await publish(undefined, "");
    expect(anonymous.status).toHaveBeenCalledWith(401);
    expect(send).not.toHaveBeenCalled();
    const denied = await publish();
    expect(denied.status).toHaveBeenCalledWith(403);
    expect(denied.revalidate).not.toHaveBeenCalled();
  });

  it("reports a committed change with a pending refresh without repeating the mutation", async () => {
    const send = vi.fn().mockResolvedValue(Response.json({ success: true }));
    vi.stubGlobal("fetch", send);
    vi.spyOn(console, "error").mockImplementation(() => {});
    const res = await publish(vi.fn().mockRejectedValue(new Error("Origin unavailable")));
    expect(res.setHeader).toHaveBeenCalledWith("X-Room-Catalog-Refresh", "pending");
    expect(res.status).toHaveBeenCalledWith(200);
    expect(send).toHaveBeenCalledTimes(1);
  });
});
