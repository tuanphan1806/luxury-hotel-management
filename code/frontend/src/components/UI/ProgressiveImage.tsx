"use client";

import { useEffect, useState } from "react";
import Image, { type ImageProps } from "next/image";
import { resolveMediaSource } from "@/lib/media-url";

type ProgressiveImageProps = ImageProps & {
  loaderClassName?: string;
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
  ...props
}: ProgressiveImageProps) {
  const [isLoaded, setIsLoaded] = useState(false);
  const shouldPrioritize = Boolean(priority);
  const resolvedSrc = resolveMediaSource(src);

  useEffect(() => {
    setIsLoaded(false);
  }, [resolvedSrc]);

  return (
    <>
      <span
        aria-hidden="true"
        className={`image-loading-surface absolute inset-0 ${isLoaded ? "image-loading-surface-done" : ""} ${loaderClassName}`}
      />
      <Image
        {...props}
        src={resolvedSrc}
        alt={alt}
        quality={quality}
        decoding={decoding}
        priority={priority}
        fetchPriority={shouldPrioritize ? "high" : fetchPriority}
        loading={shouldPrioritize ? "eager" : loading}
        data-priority={shouldPrioritize ? "true" : "false"}
        data-loaded={isLoaded ? "true" : "false"}
        className={`progressive-media ${className}`}
        onLoad={(event) => {
          setIsLoaded(true);
          onLoad?.(event);
        }}
        onError={(event) => {
          setIsLoaded(true);
          onError?.(event);
        }}
      />
    </>
  );
}
