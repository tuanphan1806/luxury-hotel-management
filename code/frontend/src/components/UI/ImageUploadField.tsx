"use client";

import React, { useEffect, useId, useRef, useState } from "react";
import { getApiErrorMessage } from "@/lib/api";
import {
  formatUploadFileSize,
  IMAGE_UPLOAD_ACCEPT,
  REFUND_PROOF_UPLOAD_ACCEPT,
  ImageUploadError,
  isUploadCancelled,
  type ImageUploadFolder,
  type UploadedImage,
  uploadImage,
  validateImageFile,
} from "@/lib/image-upload";
import { useLanguage } from "@/components/i18n/LanguageProvider";

interface ImageUploadFieldProps {
  id?: string;
  folder: ImageUploadFolder;
  value?: string;
  label: string;
  alt: string;
  description?: string;
  disabled?: boolean;
  tone?: "light" | "dark";
  aspect?: "square" | "landscape";
  className?: string;
  onUploaded: (image: UploadedImage) => void;
  onUploadingChange?: (uploading: boolean) => void;
  allowPdf?: boolean;
  refundId?: string;
}

type UploadStatus = "idle" | "uploading" | "success" | "error";

const errorMessage = (
  error: unknown,
  localize: (vi?: string | null, en?: string | null) => string,
  allowPdf = false,
) => {
  if (error instanceof ImageUploadError) {
    if (error.code === "EMPTY_FILE") return localize("Tệp ảnh đang trống.", "The image file is empty.");
    if (error.code === "FILE_TOO_LARGE") return localize("Ảnh không được vượt quá 5 MB.", "The image must not exceed 5 MB.");
    if (error.code === "UNSUPPORTED_TYPE") return allowPdf
      ? localize("Chỉ chấp nhận JPEG, PNG, WebP hoặc PDF.", "Only JPEG, PNG, WebP or PDF files are accepted.")
      : localize("Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP.", "Only JPEG, PNG or WebP images are accepted.");
    return localize("Máy chủ không trả về đường dẫn ảnh hợp lệ.", "The server did not return a valid image URL.");
  }
  return getApiErrorMessage(error, localize("Không thể tải ảnh. Vui lòng thử lại.", "Unable to upload the image. Please try again."));
};

export default function ImageUploadField({
  id,
  folder,
  value = "",
  label,
  alt,
  description,
  disabled = false,
  tone = "light",
  aspect = "landscape",
  className = "",
  onUploaded,
  onUploadingChange,
  allowPdf = false,
  refundId,
}: ImageUploadFieldProps) {
  const { localize } = useLanguage();
  const generatedId = useId().replace(/:/g, "");
  const inputId = id || `image-upload-${generatedId}`;
  const helpId = `${inputId}-help`;
  const statusId = `${inputId}-status`;
  const inputRef = useRef<HTMLInputElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const operationRef = useRef(0);
  const uploadBaseValueRef = useRef(value);
  const isUploadingRef = useRef(false);
  const objectUrlRef = useRef<string | null>(null);
  const lastUploadedUrlRef = useRef("");
  const onUploadingChangeRef = useRef(onUploadingChange);
  const [status, setStatus] = useState<UploadStatus>("idle");
  const [message, setMessage] = useState("");
  const [progress, setProgress] = useState(0);
  const [previewUrl, setPreviewUrl] = useState(value);
  const [fileSummary, setFileSummary] = useState("");
  const [isPdfPreview, setIsPdfPreview] = useState(false);

  useEffect(() => {
    onUploadingChangeRef.current = onUploadingChange;
  }, [onUploadingChange]);

  useEffect(() => {
    if (value === lastUploadedUrlRef.current) return;
    if (isUploadingRef.current && value !== uploadBaseValueRef.current) {
      operationRef.current += 1;
      abortRef.current?.abort();
      abortRef.current = null;
      isUploadingRef.current = false;
      onUploadingChangeRef.current?.(false);
    }
    setPreviewUrl(value);
    setIsPdfPreview(false);
    setFileSummary("");
    setStatus("idle");
    setMessage("");
    setProgress(0);
  }, [value]);

  useEffect(() => () => {
    operationRef.current += 1;
    abortRef.current?.abort();
    isUploadingRef.current = false;
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    onUploadingChangeRef.current?.(false);
  }, []);

  const replacePreview = (nextUrl: string, objectUrl = false) => {
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    objectUrlRef.current = objectUrl ? nextUrl : null;
    setPreviewUrl(nextUrl);
  };

  const handleFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // Reset immediately so the same file can be selected again after an error.
    event.target.value = "";
    if (!file || disabled || status === "uploading") return;

    try {
      validateImageFile(file, allowPdf);
    } catch (error: unknown) {
      setStatus("error");
      setMessage(errorMessage(error, localize, allowPdf));
      setFileSummary(`${file.name} · ${formatUploadFileSize(file.size)}`);
      return;
    }

    const operation = operationRef.current + 1;
    operationRef.current = operation;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    uploadBaseValueRef.current = value;
    isUploadingRef.current = true;

    const selectedPdf = file.type.toLowerCase() === "application/pdf";
    setIsPdfPreview(selectedPdf);
    if (selectedPdf) replacePreview("");
    else replacePreview(URL.createObjectURL(file), true);
    setFileSummary(`${file.name} · ${formatUploadFileSize(file.size)}`);
    setStatus("uploading");
    setMessage(localize("Đang kiểm tra và tải ảnh...", "Validating and uploading image..."));
    setProgress(1);
    onUploadingChangeRef.current?.(true);

    try {
      const uploaded = await uploadImage(file, folder, {
        signal: controller.signal,
        allowPdf,
        refundId,
        onProgress: (nextProgress) => {
          if (operationRef.current === operation) setProgress(nextProgress);
        },
      });
      if (operationRef.current !== operation) return;

      lastUploadedUrlRef.current = uploaded.url;
      isUploadingRef.current = false;
      const uploadedPdf = uploaded.contentType?.toLowerCase() === "application/pdf";
      setIsPdfPreview(uploadedPdf);
      replacePreview(uploadedPdf ? "" : uploaded.url);
      setStatus("success");
      setProgress(100);
      const size = formatUploadFileSize(uploaded.size || file.size);
      const dimensions = uploaded.width && uploaded.height ? ` · ${uploaded.width}×${uploaded.height}px` : "";
      setFileSummary(`${file.name}${size ? ` · ${size}` : ""}${dimensions}`);
      setMessage(localize("Tệp đã tải lên. Hãy lưu biểu mẫu để áp dụng.", "File uploaded. Save the form to apply it."));
      onUploaded(uploaded);
    } catch (error: unknown) {
      if (operationRef.current !== operation || isUploadCancelled(error)) return;
      replacePreview(value);
      setIsPdfPreview(false);
      setStatus("error");
      setProgress(0);
      setMessage(errorMessage(error, localize, allowPdf));
    } finally {
      if (operationRef.current === operation) {
        abortRef.current = null;
        isUploadingRef.current = false;
        onUploadingChangeRef.current?.(false);
      }
    }
  };

  const isDark = tone === "dark";
  const isUploading = status === "uploading";
  const statusColor = status === "error"
    ? isDark ? "text-rose-200" : "text-rose-700"
    : status === "success"
      ? isDark ? "text-emerald-200" : "text-emerald-700"
      : isDark ? "text-white/70" : "text-[#66727C]";

  return (
    <div className={`${className}`}>
      <div className={`grid items-center gap-4 ${aspect === "square" ? "grid-cols-[5rem_minmax(0,1fr)]" : "grid-cols-[7rem_minmax(0,1fr)]"}`}>
        <div className={`relative overflow-hidden border ${aspect === "square" ? "aspect-square rounded-xl" : "aspect-[16/10] rounded-xl"} ${isDark ? "border-white/20 bg-white/10" : "border-[#0F2A43]/10 bg-[#EAE2D2]"}`}>
          {isPdfPreview ? (
            <div className={`flex h-full items-center justify-center px-2 text-center text-xs font-bold ${isDark ? "text-white/75" : "text-[#66727C]"}`}>
              PDF
            </div>
          ) : previewUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- blob previews and backend-hosted URLs are intentionally dynamic.
            <img key={previewUrl} src={previewUrl} alt={alt} className="image-preview-enter h-full w-full object-cover" />
          ) : (
            <div className={`flex h-full items-center justify-center px-2 text-center text-[10px] font-bold ${isDark ? "text-white/55" : "text-[#66727C]"}`}>
              {localize("Chưa có ảnh", "No image")}
            </div>
          )}
          {isUploading && <span aria-hidden="true" className="image-loading-surface absolute inset-0" />}
        </div>

        <div className="min-w-0">
          <label htmlFor={inputId} className={`block text-xs font-bold ${isDark ? "text-white" : "text-[#66727C]"}`}>
            {label}
          </label>
          <p id={helpId} className={`mt-1 text-[11px] leading-4 ${isDark ? "text-white/55" : "text-[#66727C]"}`}>
            {description || (allowPdf
              ? localize("JPEG, PNG, WebP hoặc PDF · tối đa 5 MB.", "JPEG, PNG, WebP or PDF · up to 5 MB.")
              : localize("JPEG, PNG hoặc WebP · tối đa 5 MB.", "JPEG, PNG or WebP · up to 5 MB."))}
          </p>
          <input
            ref={inputRef}
            id={inputId}
            type="file"
            accept={allowPdf ? REFUND_PROOF_UPLOAD_ACCEPT : IMAGE_UPLOAD_ACCEPT}
            disabled={disabled || isUploading}
            aria-describedby={`${helpId} ${statusId}`}
            onChange={handleFileChange}
            className="peer sr-only"
          />
          <label
            htmlFor={inputId}
            aria-disabled={disabled || isUploading}
            className={`mt-2 inline-flex min-h-10 items-center justify-center rounded-lg px-4 text-xs font-bold transition peer-focus-visible:ring-2 peer-focus-visible:ring-[#B8944F] peer-focus-visible:ring-offset-2 ${disabled || isUploading ? "cursor-not-allowed opacity-55" : "cursor-pointer"} ${isDark ? "bg-[#B8944F] text-[#0F2A43] hover:bg-[#D2B879] peer-focus-visible:ring-offset-[#0F2A43]" : "border border-[#0F2A43]/15 bg-white text-[#0F2A43] hover:border-[#B8944F] hover:text-[#80632F]"}`}
          >
            {isUploading ? localize(`Đang tải ${progress}%`, `Uploading ${progress}%`) : value ? localize("Thay ảnh", "Replace image") : localize("Chọn ảnh", "Choose image")}
          </label>
        </div>
      </div>

      {isUploading && (
        <div className={`mt-3 h-1.5 overflow-hidden rounded-full ${isDark ? "bg-white/15" : "bg-[#0F2A43]/10"}`}>
          <div
            role="progressbar"
            aria-label={localize("Tiến độ tải ảnh", "Image upload progress")}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progress}
            className="h-full rounded-full bg-[#B8944F] transition-[width] duration-200"
            style={{ width: `${progress}%` }}
          />
        </div>
      )}
      <div id={statusId} aria-live="polite" aria-atomic="true" className="mt-2 min-h-4">
        {fileSummary && <p className={`truncate text-[10px] font-semibold ${isDark ? "text-white/55" : "text-[#66727C]"}`}>{fileSummary}</p>}
        {message && <p role={status === "error" ? "alert" : "status"} className={`mt-1 text-[11px] font-semibold leading-4 ${statusColor}`}>{message}</p>}
      </div>
    </div>
  );
}
