"use client";

import { useState } from "react";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";

const galleryCards = [
  {
    src: "/backend_proxy/galeries/g-1.jpg?v=20260722-about-story",
    vi: "Sảnh đón ngập ánh sáng",
    en: "A light-filled arrival lobby",
  },
  {
    src: "/backend_proxy/galeries/g-6.jpg?v=20260722-about-story",
    vi: "Khoảng nghỉ bên hồ bơi",
    en: "A quiet poolside retreat",
  },
  {
    src: "/backend_proxy/galeries/g-8.jpg?v=20260722-about-story",
    vi: "Sân trong xanh mát",
    en: "A calm garden courtyard",
  },
  {
    src: "/backend_proxy/galeries/g-11.jpg?v=20260722-about-story",
    vi: "Không gian chung gần thiên nhiên",
    en: "Shared spaces close to nature",
  },
] as const;

export default function AboutGalleryStack() {
  const { localize } = useLanguage();
  const [activeIndex, setActiveIndex] = useState(0);
  const total = galleryCards.length;

  const showCard = (index: number) => setActiveIndex(index);
  const showNext = () => setActiveIndex((current) => (current + 1) % total);

  return (
    <div className="about-story-stack" data-guest-reveal>
      <div className="relative min-h-[29rem] w-full overflow-hidden sm:min-h-[34rem] lg:min-h-[38rem]" aria-live="polite">
        {galleryCards.map((card, index) => {
          const stackPosition = (index - activeIndex + total) % total;
          const title = localize(card.vi, card.en);
          const isActive = stackPosition === 0;

          return (
            <button
              key={card.src}
              type="button"
              data-stack-position={stackPosition}
              onClick={isActive ? showNext : () => showCard(index)}
              tabIndex={isActive ? 0 : -1}
              aria-label={isActive
                ? localize(`Xem ảnh tiếp theo sau ${title}`, `Show the next image after ${title}`)
                : localize(`Đưa ảnh ${title} lên phía trước`, `Bring ${title} to the front`)}
              className="about-story-stack-card absolute left-[3%] top-1/2 aspect-[4/5] w-[66%] overflow-hidden rounded-[0.35rem] border-[0.55rem] border-[#FFFDF8] bg-[#FFFDF8] text-left shadow-[0_26px_70px_rgba(15,42,67,0.22)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-4 sm:w-[62%] lg:w-[64%]"
            >
              <span className="relative block h-full overflow-hidden rounded-[0.15rem] bg-[#E5E9ED]">
                <ProgressiveImage
                  src={card.src}
                  alt={title}
                  fill
                  priority={index === 0}
                  sizes="(min-width: 1024px) 34vw, (min-width: 640px) 55vw, 70vw"
                  className="object-cover"
                />
                <span aria-hidden="true" className="absolute inset-0 bg-gradient-to-t from-[#091E30]/76 via-transparent to-transparent" />
                <span className="absolute right-3 top-3 rounded-full border border-white/35 bg-[#0F2A43]/82 px-3 py-1 text-[9px] font-bold uppercase tracking-[0.16em] text-[#F1F0EA] backdrop-blur-sm">
                  {String(index + 1).padStart(2, "0")}
                </span>
                <span className="absolute inset-x-0 bottom-0 p-4 text-white sm:p-5">
                  <span className="block text-[9px] font-bold uppercase tracking-[0.2em] text-[#E5C98E]">
                    {localize("Không gian Luxury Hotel", "Luxury Hotel spaces")}
                  </span>
                  <span className="mt-1 block font-serif text-lg font-bold leading-tight sm:text-2xl">{title}</span>
                </span>
              </span>
            </button>
          );
        })}
      </div>

      <div className="mt-4 flex flex-wrap items-center justify-between gap-4 border-t border-[#0F2A43]/14 pt-4">
        <div className="flex items-baseline gap-3 text-[#0F2A43]">
          <strong className="font-serif text-2xl tabular-nums">{String(activeIndex + 1).padStart(2, "0")}</strong>
          <span aria-hidden="true" className="h-px w-10 bg-[#B8944F]" />
          <span className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#66727C]">
            {String(total).padStart(2, "0")} {localize("không gian", "spaces")}
          </span>
        </div>

        <div className="flex items-center gap-1" aria-label={localize("Chọn ảnh không gian", "Choose a hotel image")}>
          {galleryCards.map((card, index) => (
            <button
              key={card.src}
              type="button"
              onClick={() => showCard(index)}
              aria-pressed={activeIndex === index}
              aria-label={localize(`Xem ảnh ${index + 1}: ${card.vi}`, `View image ${index + 1}: ${card.en}`)}
              className="group flex h-11 w-11 items-center justify-center rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
            >
              <span className={`block h-1.5 rounded-full transition-[width,background-color] duration-200 ${activeIndex === index ? "w-7 bg-[#0F2A43]" : "w-2 bg-[#B8944F]/55 group-hover:bg-[#B8944F]"}`} />
            </button>
          ))}
        </div>
      </div>

      <p className="mt-2 text-xs italic leading-5 text-[#66727C]">
        {localize("Chạm vào ảnh phía trước để khám phá không gian tiếp theo.", "Select the front image to explore the next space.")}
      </p>
    </div>
  );
}
