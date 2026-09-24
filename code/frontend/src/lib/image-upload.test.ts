import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@/lib/api";
import { IMAGE_UPLOAD_MAX_BYTES, uploadImage, validateImageFile } from "./image-upload";

vi.mock("@/lib/api", () => ({ apiClient: { post: vi.fn() } }));

describe("media upload contract", () => {
  beforeEach(() => vi.mocked(apiClient.post).mockReset());

  it("rejects empty, oversized and executable uploads before contacting the server", async () => {
    for (const [file, code] of [
      [new File([], "empty.png", { type: "image/png" }), "EMPTY_FILE"],
      [new File([new Uint8Array(IMAGE_UPLOAD_MAX_BYTES + 1)], "large.png", { type: "image/png" }), "FILE_TOO_LARGE"],
      [new File(["script"], "script.svg", { type: "image/svg+xml" }), "UNSUPPORTED_TYPE"],
    ] as const) {
      await expect(uploadImage(file, "GALLERY")).rejects.toMatchObject({ code });
    }
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("permits a PDF only when the refund-proof caller explicitly allows it", () => {
    const proof = new File(["%PDF"], "proof.pdf", { type: "application/pdf" });
    expect(() => validateImageFile(proof)).toThrow("UNSUPPORTED_TYPE");
    expect(() => validateImageFile(proof, true)).not.toThrow();
  });

  it("preserves the returned Cloudinary URL and waits for server success before reporting completion", async () => {
    const onProgress = vi.fn();
    const controller = new AbortController();
    vi.mocked(apiClient.post).mockImplementationOnce(async (_url, body, config) => {
      expect((body as FormData).get("folder")).toBe("GALLERY");
      expect(config?.signal).toBe(controller.signal);
      config?.onUploadProgress?.({ loaded: 10, total: 10, bytes: 10, lengthComputable: true });
      expect(onProgress).toHaveBeenLastCalledWith(95);
      return { data: { data: { url: " https://res.cloudinary.com/demo/image/upload/room.webp ", assetId: "12", width: 800, height: -1, contentType: "image/webp", objectKey: "room", storedFileName: "room.webp", size: 10 } } };
    });
    const result = await uploadImage(new File(["image"], "room.webp", { type: "image/webp" }), "GALLERY", { onProgress, signal: controller.signal });
    expect(result).toMatchObject({ url: "https://res.cloudinary.com/demo/image/upload/room.webp", assetId: 12, width: 800, height: undefined, fileName: "room.webp" });
    expect(onProgress).toHaveBeenLastCalledWith(100);
  });

  it.each([null, "invalid", {}, { data: {} }, { url: " " }])("rejects malformed upload success without reporting completion: %j", async (payload) => {
    const onProgress = vi.fn();
    vi.mocked(apiClient.post).mockResolvedValueOnce({ data: payload });
    await expect(uploadImage(new File(["image"], "room.jpg", { type: "image/jpeg" }), "ROOM_TYPES", { onProgress })).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
    expect(onProgress).not.toHaveBeenCalledWith(100);
  });

  it("accepts the flat response contract and includes the refund association", async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({ data: { url: "/backend_proxy/files/proof.pdf", fileName: "proof.pdf" } });
    const result = await uploadImage(new File(["%PDF"], "proof.pdf", { type: "application/pdf" }), "REFUND_PROOFS", { allowPdf: true, refundId: "refund-1" });
    expect(result.fileName).toBe("proof.pdf");
    expect((vi.mocked(apiClient.post).mock.calls[0][1] as FormData).get("refundId")).toBe("refund-1");
  });
});
