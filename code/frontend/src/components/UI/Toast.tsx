"use client";

import React, { type CSSProperties, useEffect } from 'react';

export interface ToastProps {
  message: string;
  type: 'success' | 'error' | 'info';
  onClose: () => void;
  duration?: number;
}

export default function Toast({ message, type, onClose, duration = 4000 }: ToastProps) {
  useEffect(() => {
    const timer = setTimeout(() => {
      onClose();
    }, duration);
    return () => clearTimeout(timer);
  }, [onClose, duration]);

  const bgStyles = {
    success: 'bg-emerald-50 border border-emerald-200 text-emerald-800 shadow-emerald-100',
    error: 'bg-rose-50 border border-rose-200 text-rose-800 shadow-rose-100',
    info: 'bg-amber-50 border border-amber-200 text-amber-800 shadow-amber-100',
  };

  const iconStyles = {
    success: (
      <svg className="w-5 h-5 text-emerald-600" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    ),
    error: (
      <svg className="w-5 h-5 text-rose-600" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    ),
    info: (
      <svg className="w-5 h-5 text-amber-600" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
      </svg>
    ),
  };

  const progressStyles = {
    success: 'bg-emerald-500',
    error: 'bg-rose-500',
    info: 'bg-amber-500',
  };

  return (
    <div
      className={`toast-enter fixed bottom-[calc(1.5rem+env(safe-area-inset-bottom))] right-4 z-[100] flex max-w-[calc(100vw-2rem)] items-center gap-3 overflow-hidden rounded-xl px-5 py-4 shadow-xl sm:right-6 sm:max-w-sm ${bgStyles[type]}`}
      role={type === 'error' ? 'alert' : 'status'}
      aria-live={type === 'error' ? 'assertive' : 'polite'}
    >
      <span className="flex-shrink-0">{iconStyles[type]}</span>
      <p className="text-sm font-semibold tracking-wide flex-grow leading-snug">{message}</p>
      <button 
        onClick={onClose} 
        className="ml-2 flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-gray-500 transition-colors hover:bg-black/5 hover:text-gray-700 focus-visible:outline-none"
        aria-label="Đóng thông báo / Close notification"
      >
        <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
      <span
        aria-hidden="true"
        className={`toast-progress absolute inset-x-0 bottom-0 h-1 ${progressStyles[type]}`}
        style={{ '--toast-duration': `${duration}ms` } as CSSProperties}
      />
    </div>
  );
}
