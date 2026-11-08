"use client";

import { useEffect } from "react";
import { watchBackendWakeup } from "@/lib/backend-wakeup";

export default function BackendWakeup() {
  useEffect(() => watchBackendWakeup(document), []);
  return null;
}
