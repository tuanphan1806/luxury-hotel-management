"use client";

import React, { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

interface ViewportModalProps {
  open: boolean;
  onClose: () => void;
  labelledBy: string;
  describedBy?: string;
  busy?: boolean;
  children: React.ReactNode;
  panelClassName?: string;
  backdropClassName?: string;
  zIndexClassName?: string;
  testId?: string;
}

/**
 * Viewport-owned modal shell used by guest and operations forms.
 * It portals outside transformed layouts, traps focus, locks page scroll and
 * keeps long content inside a bounded panel instead of scrolling the backdrop.
 */
export default function ViewportModal({
  open,
  onClose,
  labelledBy,
  describedBy,
  busy = false,
  children,
  panelClassName = "max-w-xl",
  backdropClassName = "bg-[#091E30]/68",
  zIndexClassName = "z-[80]",
  testId,
}: ViewportModalProps) {
  const [mounted, setMounted] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  const busyRef = useRef(busy);

  useEffect(() => setMounted(true), []);
  useEffect(() => {
    onCloseRef.current = onClose;
    busyRef.current = busy;
  }, [busy, onClose]);

  useEffect(() => {
    if (!open || !mounted) return;

    const previousFocus = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const frame = window.requestAnimationFrame(() => {
      const preferred = panelRef.current?.querySelector<HTMLElement>("[data-modal-autofocus]");
      const firstFocusable = panelRef.current?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (preferred || firstFocusable || panelRef.current)?.focus();
    });

    const handleKeyDown = (event: KeyboardEvent) => {
      const openDialogs = document.querySelectorAll<HTMLElement>("[role='dialog'][aria-modal='true']");
      if (openDialogs[openDialogs.length - 1] !== panelRef.current) return;
      if (event.key === "Escape") {
        if (!busyRef.current) onCloseRef.current();
        return;
      }
      if (event.key !== "Tab" || !panelRef.current) return;

      const focusable = Array.from(
        panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter((element) => !element.hasAttribute("disabled") && element.offsetParent !== null);
      if (focusable.length === 0) {
        event.preventDefault();
        panelRef.current.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus();
    };
  }, [mounted, open]);

  if (!mounted || !open) return null;

  return createPortal(
    <div
      className={`fixed inset-0 grid place-items-center overflow-hidden p-2 sm:p-4 ${zIndexClassName} ${backdropClassName}`}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) onClose();
      }}
      data-testid={testId}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-describedby={describedBy}
        aria-busy={busy || undefined}
        tabIndex={-1}
        className={`flex max-h-[calc(100dvh-1rem)] min-h-0 w-full flex-col overflow-hidden rounded-xl border border-white/20 bg-white shadow-2xl outline-none sm:max-h-[calc(100dvh-2rem)] ${panelClassName}`}
      >
        {children}
      </div>
    </div>,
    document.body,
  );
}
