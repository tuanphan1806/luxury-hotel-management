import { defineConfig, devices } from '@playwright/test';

// These values belong only to compose.qa.yml. Never load backend .env here.
process.env.E2E_QA_API = 'http://127.0.0.1:19080';
process.env.QA_SEPAY_WEBHOOK_SECRET = 'isolated-qa-webhook-only';
process.env.QA_MERCHANT_BANK_ACCOUNT = '0000000000';

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: /(?:reservation-runtime|sepay-online-runtime|management-crud|authenticated-access)\.spec\.ts/,
  globalSetup: './tests/qa/verify-isolation.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [['list']],
  outputDir: '../../output/isolated-qa-2026-09-25/browser',
  use: {
    baseURL: 'http://localhost:13000',
    channel: 'chrome',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'desktop-chrome', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'node node_modules/next/dist/bin/next dev --turbopack --port 13000',
    url: 'http://localhost:13000',
    reuseExistingServer: false,
    timeout: 120_000,
    env: { BACKEND_INTERNAL_URL: 'http://127.0.0.1:19080', NEXT_TELEMETRY_DISABLED: '1' },
  },
});
