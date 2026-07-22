import path from 'node:path';
import { fileURLToPath } from 'node:url';
import createNextIntlPlugin from 'next-intl/plugin';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const backendOrigin = (process.env.BACKEND_INTERNAL_URL || 'http://localhost:8080').replace(/\/+$/, '');
const withNextIntl = createNextIntlPlugin('./src/i18n/request.ts');
const standaloneOutputEnabled = process.env.NEXT_DISABLE_STANDALONE !== 'true';

const imageOrigins = [
  'https://images.unsplash.com',
  'https://plus.unsplash.com',
  'https://res.cloudinary.com',
  'http://localhost:8080',
  process.env.NEXT_PUBLIC_BACKEND_URL,
  process.env.NEXT_PUBLIC_API_URL,
  process.env.BACKEND_INTERNAL_URL,
].filter(Boolean);

const remotePatterns = Array.from(new Map(imageOrigins.flatMap((origin) => {
  try {
    const parsed = new URL(origin);
    if (!['http:', 'https:'].includes(parsed.protocol)) return [];
    const pattern = {
      protocol: parsed.protocol.slice(0, -1),
      hostname: parsed.hostname,
      port: parsed.port,
      pathname: '/**',
    };
    return [[`${pattern.protocol}://${pattern.hostname}:${pattern.port}`, pattern]];
  } catch {
    return [];
  }
})).values());

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Produces a minimal self-contained server for Docker while remaining
  // compatible with Vercel deployments. Windows contributors can disable
  // symlink-heavy trace copying with NEXT_DISABLE_STANDALONE=true.
  ...(standaloneOutputEnabled ? { output: 'standalone' } : {}),
  poweredByHeader: false,
  compress: true,
  distDir: process.env.NEXT_DIST_DIR
    || (process.env.NODE_ENV === 'development' ? '.next-dev' : '.next'),
  outputFileTracingRoot: __dirname,
  images: {
    localPatterns: [{ pathname: '/**' }],
    remotePatterns,
    formats: ['image/webp'],
    qualities: [78, 92],
    minimumCacheTTL: 86_400,
  },
  async rewrites() {
    return [
      {
        source: '/backend_proxy/:path*',
        destination: `${backendOrigin}/:path*`,
      },
    ];
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Content-Security-Policy', value: "object-src 'none'; base-uri 'self'; frame-ancestors 'none'" },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'X-Frame-Options', value: 'DENY' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
        ],
      },
    ];
  },
};

export default withNextIntl(nextConfig);
