import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { watchBackendWakeup } from "./backend-wakeup";

class Visibility extends EventTarget {
  visibilityState: DocumentVisibilityState = "visible";
  change(state: DocumentVisibilityState) {
    this.visibilityState = state;
    this.dispatchEvent(new Event("visibilitychange"));
  }
}

const up = () => new Response(JSON.stringify({ status: "UP" }), { status: 200 });
let stop: (() => void) | undefined;

describe("visitor-triggered backend wakeup", () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => {
    stop?.();
    stop = undefined;
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("reaches the live readiness endpoint even when the page uses cached catalog data, then stops", async () => {
    const fetchMock = vi.fn().mockImplementation(up);
    vi.stubGlobal("fetch", fetchMock);
    stop = watchBackendWakeup(new Visibility());
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledWith("/backend_proxy/actuator/health/readiness", {
      method: "GET", cache: "no-store", credentials: "omit",
      headers: { Accept: "application/json" }, signal: expect.any(AbortSignal),
    });
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not wake for a background tab, and cancels the discarded Strict Mode mount", async () => {
    const fetchMock = vi.fn().mockImplementation(up);
    vi.stubGlobal("fetch", fetchMock);
    const visibility = new Visibility();
    watchBackendWakeup(visibility)();
    visibility.change("hidden");
    stop = watchBackendWakeup(visibility);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetchMock).not.toHaveBeenCalled();
    visibility.change("visible");
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("keeps trying a 140-second cold start and only accepts JSON readiness UP", async () => {
    const started = Date.now();
    const fetchMock = vi.fn().mockImplementation(() => Date.now() - started < 140_000
      ? new Response("Starting...", { status: 200 }) : up());
    vi.stubGlobal("fetch", fetchMock);
    stop = watchBackendWakeup(new Visibility());
    await vi.advanceTimersByTimeAsync(160_000);
    const attempts = fetchMock.mock.calls.length;
    expect(attempts).toBeGreaterThan(1);
    expect(attempts).toBeLessThanOrEqual(10);
    await vi.advanceTimersByTimeAsync(600_000);
    expect(fetchMock).toHaveBeenCalledTimes(attempts);
  });

  it("stops a prolonged outage within the recovery window", async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Response(null, { status: 503 }));
    vi.stubGlobal("fetch", fetchMock);
    stop = watchBackendWakeup(new Visibility());
    await vi.advanceTimersByTimeAsync(210_000);
    const attempts = fetchMock.mock.calls.length;
    expect(attempts).toBeLessThanOrEqual(13);
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(fetchMock).toHaveBeenCalledTimes(attempts);
  });

  it("aborts an unresponsive request and the current request when the visitor leaves", async () => {
    const signals: AbortSignal[] = [];
    vi.stubGlobal("fetch", vi.fn().mockImplementation((_url, init: RequestInit) => new Promise((_resolve, reject) => {
      const signal = init.signal!;
      signals.push(signal);
      signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
    })));
    stop = watchBackendWakeup(new Visibility());
    await vi.advanceTimersByTimeAsync(12_000);
    expect(signals[0].aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(signals).toHaveLength(2);
    stop();
    expect(signals[1].aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(600_000);
    expect(signals).toHaveLength(2);
  });

  it("pauses in the background without renewing an expired retry budget", async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Response(null, { status: 503 }));
    vi.stubGlobal("fetch", fetchMock);
    const visibility = new Visibility();
    stop = watchBackendWakeup(visibility);
    await vi.advanceTimersByTimeAsync(0);
    visibility.change("hidden");
    await vi.advanceTimersByTimeAsync(300_000);
    visibility.change("visible");
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("wakes again when the visitor returns after an idle window, without polling while they are away", async () => {
    const fetchMock = vi.fn().mockImplementation(up);
    vi.stubGlobal("fetch", fetchMock);
    const visibility = new Visibility();
    stop = watchBackendWakeup(visibility);
    await vi.advanceTimersByTimeAsync(0);
    visibility.change("hidden");
    await vi.advanceTimersByTimeAsync(16 * 60_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    visibility.change("visible");
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it.each(["45", "300"])("respects Retry-After %s without extending the overall deadline", async (header) => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(null, { status: 429, headers: { "Retry-After": header } }))
      .mockImplementation(up);
    vi.stubGlobal("fetch", fetchMock);
    stop = watchBackendWakeup(new Visibility());
    await vi.advanceTimersByTimeAsync(44_999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(300_000);
    expect(fetchMock).toHaveBeenCalledTimes(header === "45" ? 2 : 1);
  });

  it("does not loop when the readiness route is misconfigured", async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Response(null, { status: 404 }));
    vi.stubGlobal("fetch", fetchMock);
    stop = watchBackendWakeup(new Visibility());
    await vi.advanceTimersByTimeAsync(600_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
