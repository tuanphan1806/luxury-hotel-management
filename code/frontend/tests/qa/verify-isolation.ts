import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

export default function verifyIsolation() {
  const [backend, database] = JSON.parse(execFileSync('docker', [
    'inspect', 'hotel-isolated-qa-backend-1', 'hotel-isolated-qa-postgres-1',
  ], { encoding: 'utf8' }));
  for (const container of [backend, database]) {
    assert.equal(container.Config.Labels['com.docker.compose.project'], 'hotel-isolated-qa');
    assert.equal(container.State.Health.Status, 'healthy');
    assert.deepEqual(Object.keys(container.NetworkSettings.Networks), ['hotel-isolated-qa_qa']);
  }
  const [network] = JSON.parse(execFileSync('docker', ['network', 'inspect', 'hotel-isolated-qa_qa'], { encoding: 'utf8' }));
  assert.equal(network.Internal, true, 'QA backend must not reach real providers');
  const env = Object.fromEntries(backend.Config.Env.map((entry: string) => {
    const index = entry.indexOf('=');
    return [entry.slice(0, index), entry.slice(index + 1)];
  }));
  assert.equal(env.DATABASE_URL, 'jdbc:postgresql://postgres:5432/hotel_qa');
  assert.equal(env.SEPAY_WEBHOOK_HMAC_SECRET, process.env.QA_SEPAY_WEBHOOK_SECRET);
  assert.equal(env.SEPAY_WEBHOOK_API_KEY, '', 'QA exercises HMAC mode, not the alternative API-key mode');
  assert.equal(env.MERCHANT_BANK_ACCOUNT_NUMBER, process.env.QA_MERCHANT_BANK_ACCOUNT);
  assert.equal(env.SEPAY_RECONCILIATION_ENABLED, 'false');
  assert.equal(env.GEMINI_API_KEY, '');
  assert.equal(env.VERIFICATION_SENDGRID_API_KEY, '');
  assert.equal(env.APP_UPLOAD_STORAGE, 'local');
  assert.equal(env.PRICING_ENGINE_V2_REQUIRE_QUOTE, 'true');
  assert.equal(database.Mounts.find((mount: { Destination: string }) => mount.Destination === '/var/lib/postgresql/data')?.Name, 'hotel-isolated-qa_qa-data');
  assert.deepEqual(backend.HostConfig.PortBindings, {});
  const [proxy] = JSON.parse(execFileSync('docker', ['inspect', 'hotel-isolated-qa-proxy-1'], { encoding: 'utf8' }));
  assert.equal(proxy.Config.Labels['com.docker.compose.project'], 'hotel-isolated-qa');
  assert.deepEqual(proxy.HostConfig.PortBindings['8080/tcp'], [{ HostIp: '127.0.0.1', HostPort: '19080' }]);
  process.env.E2E_ISOLATION_VERIFIED = 'hotel-isolated-qa';
}
