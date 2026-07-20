import React from 'react';

interface InputFieldProps {
  id: string;
  label: string;
  type?: string;
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  placeholder?: string;
  error?: string;
}

export default function InputField({ id, label, type = 'text', value, onChange, placeholder = '', error }: InputFieldProps) {
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-text-dark mb-1">
        {label}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        className={`w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-accent-gold ${error ? 'border-red-500' : ''}`}
        required
      />
      {error && <p className="text-red-600 text-sm mt-1">{error}</p>}
    </div>
  );
}
