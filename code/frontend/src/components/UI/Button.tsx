import React from 'react';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  loading?: boolean;
  loadingLabel?: string;
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
}

const variants = {
  primary: 'border border-transparent bg-[#0F2A43] text-white hover:bg-[#173A58]',
  secondary: 'border border-[#0F2A43]/18 bg-[#FBFAF6] text-[#0F2A43] hover:border-[#B8944F] hover:bg-[#F0EADF]',
  ghost: 'border border-transparent bg-transparent text-[#0F2A43] hover:bg-[#0F2A43]/6',
  danger: 'border border-transparent bg-rose-700 text-white hover:bg-rose-800',
};

export default function Button({
  children,
  type = 'button',
  className = '',
  loading = false,
  loadingLabel,
  variant = 'primary',
  disabled,
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-bold transition-[background-color,border-color,color,box-shadow,transform] duration-200 ease-out hover:-translate-y-0.5 disabled:translate-y-0 disabled:shadow-none ${variants[variant]} ${className}`}
      {...props}
    >
      {loading ? (
        <>
          <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />
          <span>{loadingLabel || children}</span>
        </>
      ) : children}
    </button>
  );
}
