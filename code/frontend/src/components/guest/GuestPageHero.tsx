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
  contentPosition?: "left" | "center" | "right";
  contentClassName?: string;
  className?: string;
}

const heroPositionStyles = {
  left: {
    frame: "justify-start",
    copy: "text-left",
    description: "",
    actions: "",
    scrim: "linear-gradient(90deg, rgba(9, 30, 48, 0.8) 0%, rgba(15, 42, 67, 0.54) 40%, rgba(15, 42, 67, 0) 74%), linear-gradient(0deg, rgba(9, 30, 48, 0.54) 0%, rgba(9, 30, 48, 0) 54%)",
  },
  center: {
    frame: "justify-center",
    copy: "text-center",
    description: "mx-auto",
    actions: "justify-center",
    scrim: "linear-gradient(90deg, rgba(9, 30, 48, 0.2) 0%, rgba(9, 30, 48, 0.66) 24%, rgba(9, 30, 48, 0.66) 76%, rgba(9, 30, 48, 0.2) 100%), linear-gradient(0deg, rgba(9, 30, 48, 0.5) 0%, rgba(9, 30, 48, 0.08) 62%)",
  },
  right: {
    frame: "justify-start md:justify-end",
    copy: "text-left md:text-right",
    description: "md:ml-auto",
    actions: "md:justify-end",
    scrim: "linear-gradient(270deg, rgba(9, 30, 48, 0.82) 0%, rgba(15, 42, 67, 0.56) 40%, rgba(15, 42, 67, 0.05) 74%), linear-gradient(0deg, rgba(9, 30, 48, 0.52) 0%, rgba(9, 30, 48, 0) 54%)",
  },
} as const;

/** Shared visual contract for the first image on guest discovery pages. */
export default function GuestPageHero({
  imageSrc,
  imageAlt,
  eyebrow,
  title,
  description,
  actions,
  contentPosition = "left",
  contentClassName = "",
  className = "",
}: GuestPageHeroProps) {
  const position = heroPositionStyles[contentPosition];

  return (
    <section className={`guest-page-hero relative flex min-h-[64dvh] overflow-hidden bg-[#0F2A43] pt-20 md:min-h-[620px] ${className}`}>
      <ProgressiveImage
        src={imageSrc}
        alt={imageAlt}
        fill
        priority
        quality={92}
        sizes="100vw"
        className="guest-hero-media object-cover"
        loaderClassName="hero-image-loading-surface"
      />
      <div
        aria-hidden="true"
        data-testid="guest-page-hero-scrim"
        className="absolute inset-0"
        style={{
          backgroundImage: position.scrim,
        }}
      />

      <div className={`relative z-10 mx-auto flex w-full max-w-7xl items-center px-6 py-20 md:px-10 md:py-24 ${position.frame} ${contentClassName}`}>
        <div className={`guest-hero-copy max-w-3xl ${position.copy}`}>
          {eyebrow && (
            <p className="guest-hero-eyebrow mb-4 text-xs font-bold uppercase tracking-[0.22em] text-[#E4C77F]" style={{ textShadow: "0 1px 8px rgba(9, 30, 48, 0.85)" }}>
              {eyebrow}
            </p>
          )}
          <h1 className="guest-hero-title text-wrap-balance font-serif text-5xl font-bold leading-[1.02] text-white md:text-7xl" style={{ textShadow: "0 2px 12px rgba(0, 0, 0, 0.35)" }}>
            {title}
          </h1>
          {description && (
            <p className={`guest-hero-description mt-6 max-w-2xl text-base font-medium leading-8 text-[#F5F1E8] md:text-lg ${position.description}`} style={{ textShadow: "0 1px 8px rgba(0, 0, 0, 0.4)" }}>
              {description}
            </p>
          )}
          {actions && <div className={`guest-hero-actions mt-8 flex flex-wrap gap-3 ${position.actions}`}>{actions}</div>}
        </div>
      </div>
    </section>
  );
}
