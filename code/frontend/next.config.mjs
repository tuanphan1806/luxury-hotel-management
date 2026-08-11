import path from 'node:path';
import { fileURLToPath } from 'node:url';
import createNextIntlPlugin from 'next-intl/plugin';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const backendOrigin = (process.env.BACKEND_INTERNAL_URL || 'http://localhost:8080').replace(/\/+$/, '');
const withNextIntl = createNextIntlPlugin('./src/i18n/request.ts');
const standaloneOutputEnabled = process.env.NEXT_DISABLE_STANDALONE !== 'true'
  && (process.platform !== 'win32' || process.env.NEXT_ENABLE_STANDALONE === 'true');
const localHttpSources = process.env.NODE_ENV === 'development'
  ? ' http://localhost:* http://127.0.0.1:*'
  : '';
const localSocketSources = process.env.NODE_ENV === 'development'
  ? ' ws://localhost:* ws://127.0.0.1:*'
  : '';

// Keep the policy compatible with Next.js hydration and the existing
// backend/OAuth redirect flow while denying all resource types that are not
// explicitly needed by the application. A nonce-based script policy can be
// introduced later with per-request middleware; this static baseline already
// prevents object/frame embedding and limits network-capable resources.
const contentSecurityPolicy = [
  "default-src 'self'",
  "base-uri 'self'",
  "object-src 'none'",
  "frame-ancestors 'none'",
  "frame-src 'none'",
  "form-action 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline' https:",
  `img-src 'self' data: blob: https:${localHttpSources}`,
  "font-src 'self' data: https:",
  `connect-src 'self' https: wss:${localHttpSources}${localSocketSources}`,
  `media-src 'self' data: blob: https:${localHttpSources}`,
  "worker-src 'self' blob:",
  "manifest-src 'self'",
].join('; ');

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
  // Keep local QA identical whether the browser uses localhost or the numeric
  // loopback address. Production hosts remain governed by the deployed URL.
  allowedDevOrigins: ['localhost', '127.0.0.1'],
  // Linux/Docker produces a minimal self-contained server. Native Windows
  // defaults to the regular build because trace copying relies on symlinks
  // that are unavailable on many developer machines. It can still be opted
  // into explicitly with NEXT_ENABLE_STANDALONE=true.
  ...(standaloneOutputEnabled ? { output: 'standalone' } : {}),
  poweredByHeader: false,
  compress: true,
  distDir: process.env.NEXT_DIST_DIR
    || (process.env.NODE_ENV === 'development' ? '.next-dev' : '.next'),
  outputFileTracingRoot: __dirname,
  images: {
    localPatterns: [{ pathname: '/**' }],
    remotePatterns,
    deviceSizes: [640, 750, 828, 1080, 1200, 1600, 1920],
    imageSizes: [32, 48, 64, 96, 128, 256, 384],
    formats: ['image/webp'],
    qualities: [78, 82],
    minimumCacheTTL: 604_800,
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
          { key: 'Content-Security-Policy', value: contentSecurityPolicy },
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
