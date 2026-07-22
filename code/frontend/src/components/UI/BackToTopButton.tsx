"use client";

import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";

export default function BackToTopButton() {
  const pathname = usePathname();
  const [visible, setVisible] = useState(false);
  const [progress, setProgress] = useState(0);
  const [dashboardMode, setDashboardMode] = useState(false);

  useEffect(() => {
    setDashboardMode(Boolean(document.querySelector(".dashboard-shell")));
    let frame = 0;
    const update = () => {
      frame = 0;
      const scrollable = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
      setVisible(window.scrollY > Math.min(520, window.innerHeight * 0.7));
      setProgress(Math.min(100, Math.max(0, (window.scrollY / scrollable) * 100)));
    };
    const handleScroll = () => {
      if (!frame) frame = window.requestAnimationFrame(update);
    };

    update();
    window.addEventListener("scroll", handleScroll, { passive: true });
    window.addEventListener("resize", handleScroll, { passive: true });
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      window.removeEventListener("scroll", handleScroll);
      window.removeEventListener("resize", handleScroll);
    };
  }, [pathname]);

  if (!visible) return null;

  const scrollToTop = () => {
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
      || document.documentElement.classList.contains("hotel-reduce-motion");
    window.scrollTo({ top: 0, behavior: reduceMotion ? "auto" : "smooth" });
  };

  return (
    <button
      type="button"
      onClick={scrollToTop}
      aria-label="Lên đầu trang / Back to top"
      data-tooltip="Lên đầu trang"
      className={`ux-back-to-top fixed z-[75] flex h-12 w-12 items-center justify-center rounded-full border border-[#B8944F]/45 bg-[#FBFAF6]/92 text-[#0F2A43] shadow-[0_14px_40px_rgba(15,42,67,0.2)] backdrop-blur-md transition hover:-translate-y-1 hover:border-[#B8944F] hover:bg-white ${dashboardMode ? "bottom-[calc(1rem+env(safe-area-inset-bottom))] right-4 md:bottom-6 md:right-6" : "bottom-[calc(5.5rem+env(safe-area-inset-bottom))] right-4 md:bottom-6 md:right-24"}`}
    >
      <svg aria-hidden="true" viewBox="0 0 44 44" className="absolute inset-0 h-full w-full -rotate-90">
        <circle cx="22" cy="22" r="20" fill="none" stroke="currentColor" strokeOpacity="0.1" strokeWidth="1.5" />
        <circle
          cx="22"
          cy="22"
          r="20"
          fill="none"
          stroke="#B8944F"
          strokeWidth="2"
          strokeLinecap="round"
          pathLength="100"
          strokeDasharray="100"
          strokeDashoffset={100 - progress}
        />
      </svg>
      <svg aria-hidden="true" viewBox="0 0 24 24" className="relative h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="m6 14 6-6 6 6" />
      </svg>
    </button>
  );
}
