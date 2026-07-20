import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          "var(--font-be-vietnam)",
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "BlinkMacSystemFont",
          "\"Segoe UI\"",
          "sans-serif",
        ],
        serif: [
          "var(--font-playfair)",
          "Georgia",
          "Cambria",
          "\"Times New Roman\"",
          "serif",
        ],
      },
      colors: {
        primary: {
          navy: "#0F2A43",
          DEFAULT: "#0F2A43",
        },
        secondary: {
          navy: "#27445F",
          DEFAULT: "#27445F",
        },
        accent: {
          gold: "#B8944F",
          yellow: "#eab308",
          DEFAULT: "#B8944F",
        },
        bg: {
          light: "#F1F0EA",
        },
        text: {
          dark: "#0F2A43",
          light: "#66727C",
        },
        border: {
          light: "#D8DDE1",
        }
      },
    },
  },
  plugins: [],
};
export default config;
