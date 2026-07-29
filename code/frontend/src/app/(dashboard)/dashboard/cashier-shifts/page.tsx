"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import CashierShiftPanel from "@/components/dashboard/CashierShiftPanel";
import { useDashboardRole } from "@/hooks/use-dashboard-role";

export default function CashierShiftsPage() {
  const router = useRouter();
  const { role, isAdmin } = useDashboardRole();

  useEffect(() => {
    if (isAdmin) router.replace("/dashboard/statistics?tab=cashier");
  }, [isAdmin, router]);

  if (!role || isAdmin) {
    return <div className="mx-auto mt-8 h-40 w-full max-w-[1500px] animate-pulse rounded-2xl bg-[#0F2A43]/7" aria-label="Đang mở khu vực tài chính phù hợp với vai trò" />;
  }
  return <CashierShiftPanel />;
}
