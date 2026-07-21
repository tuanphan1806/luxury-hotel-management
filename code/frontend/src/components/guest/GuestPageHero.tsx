"use client";

import type { ReactNode } from "react";
import type { ImageProps } from "next/image";
import ProgressiveImage from "@/components/UI/ProgressiveImage";

interface GuestPageHeroProps {
  imageSrc: ImageProps["src"];
  imageAlt: string;
  eyebrow?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}

/** Shared visual contract for the first image on guest discovery pages. */
export default function GuestPageHero({
  imageSrc,
  imageAlt,
  eyebrow,
  title,
  description,
  actions,
  className = "",
}: GuestPageHeroProps) {
  return (
    <section className={`relative flex min-h-[64dvh] overflow-hidden bg-[#0F2A43] pt-20 md:min-h-[620px] ${className}`}>
      <ProgressiveImage
        src={imageSrc}
        alt={imageAlt}
        fill
        priority
        quality={92}
        sizes="100vw"
        className="object-cover"
        loaderClassName="hero-image-loading-surface"
      />
      <div
        aria-hidden="true"
        data-testid="guest-page-hero-scrim"
        className="absolute inset-0"
        style={{
          backgroundImage: "linear-gradient(90deg, rgba(9, 30, 48, 0.78) 0%, rgba(15, 42, 67, 0.52) 40%, rgba(15, 42, 67, 0) 72%), linear-gradient(0deg, rgba(9, 30, 48, 0.56) 0%, rgba(9, 30, 48, 0) 54%)",
        }}
      />

      <div className="relative z-10 mx-auto flex w-full max-w-7xl items-center px-6 py-20 md:px-10 md:py-24">
        <div className="max-w-3xl">
          {eyebrow && (
            <p className="mb-4 text-xs font-bold uppercase tracking-[0.22em] text-[#E4C77F]" style={{ textShadow: "0 1px 8px rgba(9, 30, 48, 0.85)" }}>
              {eyebrow}
            </p>
          )}
          <h1 className="text-wrap-balance font-serif text-5xl font-bold leading-[1.02] text-white md:text-7xl" style={{ textShadow: "0 2px 12px rgba(0, 0, 0, 0.35)" }}>
            {title}
          </h1>
          {description && (
            <p className="mt-6 max-w-2xl text-base font-medium leading-8 text-[#F5F1E8] md:text-lg" style={{ textShadow: "0 1px 8px rgba(0, 0, 0, 0.4)" }}>
              {description}
            </p>
          )}
          {actions && <div className="mt-8 flex flex-wrap gap-3">{actions}</div>}
        </div>
      </div>
    </section>
  );
}
