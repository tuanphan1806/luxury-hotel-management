import axios, { type AxiosProgressEvent } from "axios";
import { apiClient } from "@/lib/api";

export const IMAGE_UPLOAD_MAX_BYTES = 5 * 1024 * 1024;
export const IMAGE_UPLOAD_ACCEPT = "image/jpeg,image/png,image/webp";
export const REFUND_PROOF_UPLOAD_ACCEPT = `${IMAGE_UPLOAD_ACCEPT},application/pdf`;

export type ImageUploadFolder = "AVATAR" | "FACILITIES" | "GALLERY" | "ROOM_TYPES" | "ROOMS" | "REFUND_PROOFS";
export type ImageUploadErrorCode =
  | "EMPTY_FILE"
  | "FILE_TOO_LARGE"
  | "UNSUPPORTED_TYPE"
  | "INVALID_RESPONSE";

export interface UploadedImage {
  assetId?: number;
  url: string;
  objectKey?: string;
  contentType?: string;
  size?: number;
  width?: number;
  height?: number;
  fileName?: string;
}

interface UploadImageOptions {
  signal?: AbortSignal;
  onProgress?: (progress: number) => void;
  allowPdf?: boolean;
  refundId?: string;
}

const ALLOWED_IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

export class ImageUploadError extends Error {
  constructor(public readonly code: ImageUploadErrorCode) {
    super(code);
    this.name = "ImageUploadError";
  }
}

export const validateImageFile = (file: File, allowPdf = false) => {
  if (file.size <= 0) throw new ImageUploadError("EMPTY_FILE");
  if (file.size > IMAGE_UPLOAD_MAX_BYTES) throw new ImageUploadError("FILE_TOO_LARGE");
  const normalizedType = file.type.toLowerCase();
  if (!ALLOWED_IMAGE_TYPES.has(normalizedType) && !(allowPdf && normalizedType === "application/pdf")) {
    throw new ImageUploadError("UNSUPPORTED_TYPE");
  }
};

const asPositiveNumber = (value: unknown): number | undefined => {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
};

const parseUploadResponse = (payload: unknown): UploadedImage => {
  if (!payload || typeof payload !== "object") throw new ImageUploadError("INVALID_RESPONSE");

  const root = payload as Record<string, unknown>;
  const nested = root.data && typeof root.data === "object"
    ? root.data as Record<string, unknown>
    : undefined;
  const value = typeof root.url === "string" ? root : nested;
  const url = typeof value?.url === "string" ? value.url.trim() : "";

  if (!url) throw new ImageUploadError("INVALID_RESPONSE");

  return {
    assetId: asPositiveNumber(value?.assetId),
    url,
    objectKey: typeof value?.objectKey === "string" ? value.objectKey : undefined,
    contentType: typeof value?.contentType === "string" ? value.contentType : undefined,
    size: asPositiveNumber(value?.size),
    width: asPositiveNumber(value?.width),
    height: asPositiveNumber(value?.height),
    fileName: typeof value?.fileName === "string"
      ? value.fileName
      : typeof value?.storedFileName === "string"
        ? value.storedFileName
        : undefined,
  };
};

export const isUploadCancelled = (error: unknown) => axios.isCancel(error);

export async function uploadImage(
  file: File,
  folder: ImageUploadFolder,
  options: UploadImageOptions = {},
): Promise<UploadedImage> {
  validateImageFile(file, options.allowPdf);

  const body = new FormData();
  body.append("file", file);
  body.append("folder", folder);
  if (options.refundId) body.append("refundId", options.refundId);

  const response = await apiClient.post("/files/upload", body, {
    // Override the JSON default so Axios/browser can generate the multipart boundary.
    headers: { "Content-Type": "multipart/form-data" },
    signal: options.signal,
    onUploadProgress: (event: AxiosProgressEvent) => {
      const total = event.total || file.size;
      if (!total) return;
      // Keep the final 5% for the server-side validation and durable write.
      options.onProgress?.(Math.min(95, Math.max(1, Math.round((event.loaded / total) * 95))));
    },
  });

  const uploaded = parseUploadResponse(response.data);
  options.onProgress?.(100);
  return uploaded;
}

export const formatUploadFileSize = (bytes?: number) => {
  if (!bytes || bytes <= 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};
