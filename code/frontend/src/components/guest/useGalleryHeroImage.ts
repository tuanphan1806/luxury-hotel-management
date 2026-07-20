"use client";

import { useEffect, useState } from "react";
import type { ImageProps } from "next/image";
import { getPublicGalleries } from "@/lib/public-catalog";

interface GalleryHeroItem {
  title?: string;
  titleEn?: string;
  type?: string;
  imageUrl?: string;
  image?: string;
}

const normalizeSearchText = (value?: string) => String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();

const highResolutionGalleryAssets = new Set([
  "g-1.jpg",
  "g-3.jpg",
  "g-5.jpg",
  "g-6.jpg",
  "g-7.jpg",
  "g-9.jpg",
  "g-11.jpg",
  "g-12.jpg",
]);

export const resolveGalleryHeroImageSource = (value?: string) => {
  if (!value) return value;

  try {
    const pathname = new URL(value, "http://localhost").pathname;
    const filename = pathname.split("/").filter(Boolean).at(-1);
    if (filename && highResolutionGalleryAssets.has(filename)) {
      return `/backend_proxy/galeries/${filename}?v=20260717-4k`;
    }
  } catch {
    return value;
  }

  return value;
};

export function useGalleryHeroImage(
  fallback: ImageProps["src"],
  keywords: string[] = [],
  preferredIndex = 0,
) {
  const [imageSrc, setImageSrc] = useState<ImageProps["src"]>(fallback);
  const keywordKey = keywords.map(normalizeSearchText).filter(Boolean).join("|");

  useEffect(() => {
    let active = true;
    setImageSrc(fallback);

    getPublicGalleries<GalleryHeroItem>()
      .then((items) => {
        if (!active) return;
        const images = items.filter((item) => item.imageUrl || item.image);
        if (!images.length) return;

        const normalizedKeywords = keywordKey.split("|").filter(Boolean);
        const ranked = images
          .map((item, index) => {
            const searchable = normalizeSearchText(`${item.title || ""} ${item.titleEn || ""} ${item.type || ""}`);
            const score = normalizedKeywords.reduce((total, keyword) => total + (searchable.includes(keyword) ? 1 : 0), 0);
            return { item, index, score };
          })
          .sort((left, right) => right.score - left.score || left.index - right.index);

        const matchedImages = ranked.filter(({ score }) => score > 0);
        const candidates = matchedImages.length ? matchedImages : ranked;
        const selectedImage = candidates[Math.abs(preferredIndex) % candidates.length]?.item;
        const nextImage = resolveGalleryHeroImageSource(selectedImage?.imageUrl || selectedImage?.image);
        if (nextImage) setImageSrc(nextImage);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [fallback, keywordKey, preferredIndex]);

  return imageSrc;
}
