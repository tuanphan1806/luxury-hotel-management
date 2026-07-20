import Image from "next/image";
import { siteConfig } from "@/lib/siteConfig";

interface HotelBrandProps {
  className?: string;
  compact?: boolean;
  descriptor?: string;
  markClassName?: string;
  priority?: boolean;
  tone?: "default" | "inverse";
  wordmarkClassName?: string;
}

/** Shared vector signature used across guest, auth and operations surfaces. */
export default function HotelBrand({
  className = "",
  compact = false,
  descriptor = "DIRECT BOOKING",
  markClassName = "",
  priority = true,
  tone = "default",
  wordmarkClassName = "",
}: HotelBrandProps) {
  const inverse = tone === "inverse";

  return (
    <span className={`inline-flex min-w-0 items-center gap-3 ${className}`} data-hotel-brand={siteConfig.name}>
      <span className={`flex h-12 w-12 shrink-0 items-center justify-center ${markClassName}`}>
        <Image
          aria-hidden="true"
          alt=""
          src="/brand/luxury-hotel-mark.png"
          width={512}
          height={512}
          priority={priority}
          sizes="64px"
          className="h-full w-full rounded-full object-contain drop-shadow-[0_5px_12px_rgba(9,30,48,0.18)]"
        />
      </span>

      {!compact && (
        <span className="flex min-w-0 flex-col leading-[1.05]">
          <span className={`truncate font-serif text-lg font-bold tracking-[-0.015em] ${inverse ? "text-[#FBFAF6]" : "text-[#0F2A43]"} ${wordmarkClassName}`}>
            {siteConfig.name}
          </span>
          <span className={`mt-1.5 truncate text-[0.52rem] font-bold tracking-[0.3em] ${inverse ? "text-[#D8C398]" : "text-[#80632F]"}`}>
            {descriptor}
          </span>
        </span>
      )}
      {compact && <span className="sr-only">{siteConfig.name}</span>}
    </span>
  );
}
