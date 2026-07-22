"use client";

import { useState } from "react";
import Image, { type ImageProps } from "next/image";
import { resolveMediaSource } from "@/lib/media-url";

type ProgressiveImageProps = ImageProps & {
  loaderClassName?: string;
  fallbackSrc?: ImageProps["src"];
};

const mediaSourceKey = (source: ImageProps["src"]) => {
  if (typeof source === "string") return source;
  return "src" in source ? source.src : source.default.src;
};

/**
 * Reveals an image only after the browser has decoded it. The parent should be
 * positioned (usually `relative`) so the loading surface can fill its bounds.
 */
export default function ProgressiveImage({
  className = "",
  loaderClassName = "",
  alt,
  quality = 78,
  decoding = "async",
  priority = false,
  fetchPriority,
  loading,
  onLoad,
  onError,
  src,
  fallbackSrc,
  ...props
}: ProgressiveImageProps) {
  const shouldPrioritize = Boolean(priority);
  const resolvedSrc = resolveMediaSource(src);
  const resolvedFallbackSrc = fallbackSrc ? resolveMediaSource(fallbackSrc) : null;
  const sourceKey = mediaSourceKey(resolvedSrc);
  const [failedSourceKey, setFailedSourceKey] = useState<string | null>(null);
  const activeSrc = failedSourceKey === sourceKey && resolvedFallbackSrc ? resolvedFallbackSrc : resolvedSrc;
  const activeSourceKey = mediaSourceKey(activeSrc);
  const [loadedSourceKey, setLoadedSourceKey] = useState<string | null>(null);
  const isLoaded = loadedSourceKey === activeSourceKey;
  const isFallback = activeSrc !== resolvedSrc;

  return (
    <>
      <span
        aria-hidden="true"
        className={`image-loading-surface absolute inset-0 ${isLoaded ? "image-loading-surface-done" : ""} ${loaderClassName}`}
      />
      <Image
        key={activeSourceKey}
        {...props}
        src={activeSrc}
        alt={alt}
        quality={quality}
        decoding={decoding}
        priority={priority}
        fetchPriority={shouldPrioritize ? "high" : fetchPriority}
        loading={shouldPrioritize ? "eager" : loading}
        data-priority={shouldPrioritize ? "true" : "false"}
        data-loaded={isLoaded ? "true" : "false"}
        data-fallback={isFallback ? "true" : "false"}
        className={`progressive-media ${className}`}
        onLoad={(event) => {
          setLoadedSourceKey(activeSourceKey);
          onLoad?.(event);
        }}
        onError={(event) => {
          if (!isFallback && resolvedFallbackSrc) {
            setLoadedSourceKey(null);
            setFailedSourceKey(sourceKey);
            return;
          }
          setLoadedSourceKey(activeSourceKey);
          onError?.(event);
        }}
      />
    </>
  );
}
