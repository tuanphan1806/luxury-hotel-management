"use client";

import { Suspense } from "react";
import { usePathname } from "next/navigation";
import GuestMotionController from "@/components/guest/GuestMotionController";
import GuestRouteLoading from "@/components/guest/GuestRouteLoading";

export default function MainRouteTemplate({ children }: Readonly<{ children: React.ReactNode }>) {
  const pathname = usePathname();
  const hasPrerenderedCatalog = pathname === "/" || pathname === "/rooms";
  return (
    <div className="guest-route-enter min-w-0">
      <GuestMotionController />
      {hasPrerenderedCatalog ? children : <Suspense fallback={<GuestRouteLoading />}>{children}</Suspense>}
    </div>
  );
}
