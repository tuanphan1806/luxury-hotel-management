import type { SVGProps } from "react";

interface BotanicalLineArtProps extends Omit<SVGProps<SVGSVGElement>, "aria-hidden"> {
  tone?: "surface" | "inverse";
}

export default function BotanicalLineArt({ className = "", tone = "surface", ...props }: BotanicalLineArtProps) {
  const primaryColor = tone === "inverse" ? "text-[#F1F0EA]" : "text-[#0F2A43]";

  return (
    <svg
      aria-hidden="true"
      focusable="false"
      viewBox="0 0 240 320"
      fill="none"
      className={`botanical-line-art ${className}`}
      {...props}
    >
      <g
        className={primaryColor}
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
        opacity={tone === "inverse" ? "0.58" : "0.68"}
      >
        <path
          d="M43 304C72 271 73 231 96 190C119 149 157 123 193 55"
          strokeWidth="1.08"
          opacity="0.52"
        />
        <path d="M70 260C82 248 91 237 100 222M105 175C96 162 90 151 88 142M137 137C150 123 161 113 172 105" strokeWidth="0.78" opacity="0.42" />
        <path d="M118 153C139 171 156 197 172 231" strokeWidth="0.72" opacity="0.36" />

        <ellipse cx="120" cy="160" rx="83" ry="129" strokeWidth="0.8" opacity="0.24" transform="rotate(-13 120 160)" />
        <ellipse cx="120" cy="160" rx="67" ry="112" strokeWidth="0.65" opacity="0.18" transform="rotate(17 120 160)" />

        <g transform="translate(68 270) rotate(-34)">
          <path d="M0 0C-29-26-34-67 0-98C34-67 29-26 0 0Z" strokeWidth="1.45" />
          <path d="M0-6C-2-31-1-58 0-88" strokeWidth="1" />
          <path d="M0-26L-15-42M0-39L17-57M0-54L-18-70M0-68L14-80" strokeWidth="0.82" />
          <path d="M-9-18C-20-34-23-53-16-72M9-18C20-34 23-53 16-72" strokeWidth="0.58" opacity="0.65" />
        </g>

        <g transform="translate(172 231) rotate(31)">
          <path d="M0 0C-24-22-27-56 0-82C27-56 24-22 0 0Z" strokeWidth="1.35" />
          <path d="M0-5C1-27 1-49 0-73" strokeWidth="0.95" />
          <path d="M0-24L-12-37M0-37L13-51M0-51L-13-63" strokeWidth="0.78" />
          <path d="M-8-16C-16-30-18-45-13-60M8-16C16-30 18-45 13-60" strokeWidth="0.55" opacity="0.62" />
        </g>

        <g transform="translate(88 142) rotate(-16)">
          <path d="M0 0C-20-18-22-46 0-68C22-46 20-18 0 0Z" strokeWidth="1.25" />
          <path d="M0-5V-60" strokeWidth="0.9" />
          <path d="M0-21L-10-32M0-34L11-46M0-47L-9-55" strokeWidth="0.72" />
        </g>

        <g transform="translate(169 105) rotate(37)">
          <path d="M0 0C-18-16-19-40 0-59C19-40 18-16 0 0Z" strokeWidth="1.18" />
          <path d="M0-4V-51" strokeWidth="0.84" />
          <path d="M0-19L-9-28M0-31L10-40" strokeWidth="0.7" />
        </g>
      </g>

      <g
        className="text-[#B8944F]"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
        opacity={tone === "inverse" ? "0.86" : "0.8"}
      >
        <g transform="translate(178 300) rotate(44)">
          <path d="M0 0C-14-13-15-32 0-47C15-32 14-13 0 0Z" strokeWidth="1.15" />
          <path d="M0-4V-40" strokeWidth="0.78" />
          <path d="M0-17L-7-25M0-27L8-34" strokeWidth="0.62" />
        </g>

        <g transform="translate(57 83) rotate(-39)">
          <path d="M0 0C-13-11-14-28 0-42C14-28 13-11 0 0Z" strokeWidth="1.08" />
          <path d="M0-4V-35" strokeWidth="0.74" />
          <path d="M0-15L-6-22M0-24L7-30" strokeWidth="0.58" />
        </g>

        <circle cx="205" cy="61" r="3" strokeWidth="0.9" opacity="0.72" />
        <circle cx="37" cy="206" r="2.4" strokeWidth="0.8" opacity="0.64" />
      </g>
    </svg>
  );
}
