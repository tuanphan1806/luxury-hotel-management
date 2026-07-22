"use client";

import { useEffect } from "react";
import { usePathname } from "next/navigation";

const REVEAL_SELECTOR = ".deferred-section, [data-guest-reveal]";

export default function GuestMotionController() {
  const pathname = usePathname();

  useEffect(() => {
    const route = document.querySelector<HTMLElement>(".guest-route-enter");
    if (!route) return;

    const targets = Array.from(route.querySelectorAll<HTMLElement>(REVEAL_SELECTOR));
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
      || document.documentElement.classList.contains("hotel-reduce-motion");

    if (reducedMotion || !("IntersectionObserver" in window)) {
      targets.forEach((target) => target.classList.add("guest-reveal-visible"));
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("guest-reveal-visible");
          observer.unobserve(entry.target);
        });
      },
      { rootMargin: "0px 0px -8%", threshold: 0.08 },
    );

    const visibleBoundary = window.innerHeight * 0.92;
    targets.forEach((target, index) => {
      target.classList.remove("guest-reveal-ready", "guest-reveal-visible");
      target.style.setProperty("--guest-reveal-delay", `${Math.min(index % 3, 2) * 55}ms`);

      if (target.getBoundingClientRect().top <= visibleBoundary) {
        target.classList.add("guest-reveal-visible");
        return;
      }

      target.classList.add("guest-reveal-ready");
      observer.observe(target);
    });

    return () => observer.disconnect();
  }, [pathname]);

  return null;
}
