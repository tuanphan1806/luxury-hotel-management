export function requireIsolation() {
  if (process.env.E2E_ISOLATION_VERIFIED !== 'hotel-isolated-qa'
    || process.env.E2E_QA_API !== 'http://127.0.0.1:19080') {
    throw new Error('Stateful tests require compose.qa.yml and playwright.qa.config.ts; shared or deployed databases are forbidden.');
  }
}
