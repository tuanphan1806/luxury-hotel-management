import React from 'react';

interface ButtonProps {
  children: React.ReactNode;
  type?: 'button' | 'submit' | 'reset';
  className?: string;
}

export default function Button({ children, type = 'button', className = '' }: ButtonProps) {
  return (
    <button
      type={type}
      className={`bg-accent-gold hover:bg-yellow-500 text-white font-bold py-2 px-4 rounded-md transition-colors ${className}`}
    >
      {children}
    </button>
  );
}
