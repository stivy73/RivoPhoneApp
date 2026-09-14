import test from 'node:test';
import assert from 'node:assert/strict';
import { createProxy } from '../server.mjs';
import { providers, international } from '../providers.mjs';
const token = 'synthetic-device-token-0123456789abcdef';
const number = '+390212345678';
async function fixture(t, adapters, options = {}) {
  const server = createProxy({ tokens: [token], adapters, ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  return async (body, authorization = `Bearer ${token}`) => {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/v1/${body ? 'identify' : 'status'}`, {
      method: body ? 'POST' : 'GET', headers: { Authorization: authorization }, body: body ? JSON.stringify(body) : undefined
    });
    return { status: response.status, body: await response.json() };
  };
}
const request = provider => ({ number, provider, enabled: true });
test('requires strong configured authentication; no provider invoked anonymously', async t => {
  assert.throws(() => createProxy({ tokens: ['short'], adapters: {} }));
  const call = await fixture(t, { google: () => { throw Error('should not run'); } });
  assert.equal((await call(request('google'), '')).status, 401);
});
test('reports configured providers without contacting upstream', async t => {
  const call = await fixture(t, { google: null, ipqs: () => {} });
  assert.deepEqual((await call()).body.providers, ['ipqs']);
});
test('disabled Google never receives IPQS request or disabled request', async t => {
  let google = 0, ipqs = 0;
  const call = await fixture(t, { google: async () => { google++; return {}; }, ipqs: async () => { ipqs++; return {}; } });
  assert.equal((await call(request('ipqs'))).status, 200);
  assert.equal((await call({ ...request('google'), enabled: false })).status, 400);
  assert.equal(google, 0); assert.equal(ipqs, 1);
});
test('rejects hidden, emergency, invalid and non-canonical numbers', async t => {
  const call = await fixture(t, { ipqs: async () => assert.fail('upstream forbidden') });
  for (const value of ['', 'anonymous', '112', '+112', '123', '+39 0212345678', '+00000000000'])
    assert.equal((await call({ ...request('ipqs'), number: value })).status, 400);
});
test('provider failure is isolated and response does not disclose key or URL', async t => {
  const call = await fixture(t, { google: async () => { throw Error('secret-key URL'); }, ipqs: async () => ({ name: 'Example' }) });
  const failed = await call(request('google'));
  assert.equal(failed.status, 502); assert.equal(JSON.stringify(failed.body).includes('secret-key'), false);
  assert.equal((await call(request('ipqs'))).body.name, 'Example');
});
test('concurrent requests deduplicate per device/provider/number', async t => {
  let calls = 0;
  const call = await fixture(t, { ipqs: async () => { calls++; await new Promise(r => setTimeout(r, 30)); return { ttlSeconds: 60 }; } });
  const results = await Promise.all([call(request('ipqs')), call(request('ipqs'))]);
  assert.equal(calls, 1); assert.ok(results.every(r => r.status === 200));
});
test('IPQS has TTL, manual refresh and expiry; Google is not cached', async t => {
  let clock = 0, ipqs = 0, google = 0;
  const call = await fixture(t, { ipqs: async () => ({ name: String(++ipqs), ttlSeconds: 10 }),
    google: async () => ({ name: String(++google), ttlSeconds: 999 }) }, { now: () => clock });
  await call(request('ipqs')); await call(request('ipqs')); assert.equal(ipqs, 1);
  await call({ ...request('ipqs'), refresh: true }); assert.equal(ipqs, 2);
  clock = 11000; await call(request('ipqs')); assert.equal(ipqs, 3);
  await call(request('google')); await call(request('google')); assert.equal(google, 2);
});
test('rate limit rejects excess authenticated requests', async t => {
  const call = await fixture(t, {}, { limit: 1 });
  assert.equal((await call()).status, 200); assert.equal((await call()).status, 429);
});
test('Google names require exact international phone match, no suffix match', async () => {
  let sent;
  const api = providers({ GOOGLE_PLACES_API_KEY: 'synthetic' }, async (url, init) => {
    sent = { url, init };
    return new Response(JSON.stringify({ places: [
      { internationalPhoneNumber: '+44 2012345678', displayName: { text: 'Wrong country' } },
      { internationalPhoneNumber: '+39 02 12345678', displayName: { text: 'Matching business' } }
    ] }));
  });
  const result = await api.google(number);
  assert.equal(result.name, 'Matching business'); assert.equal(result.verified, true); assert.equal(result.ttlSeconds, 0);
  assert.equal(sent.url, 'https://places.googleapis.com/v1/places:searchText');
  assert.equal(JSON.parse(sent.init.body).textQuery, number);
  assert.ok(sent.init.headers['X-Goog-FieldMask'].includes('internationalPhoneNumber'));
});
test('Google with no matching phone returns no name', async () => {
  const api = providers({ GOOGLE_PLACES_API_KEY: 'synthetic' }, async () => new Response(JSON.stringify({ places: [{ displayName: { text: 'Unverified' } }] })));
  assert.equal((await api.google(number)).name, null);
});
test('IPQS preserves ambiguous business names as unverified and separates risk from spam', async () => {
  const api = providers({ IPQS_API_KEY: 'synthetic' }, async () => new Response(JSON.stringify({ success: true, name: 'Example Ltd, Example Person', fraud_score: 95, spammer: false })));
  const result = await api.ipqs(number);
  assert.equal(result.name, 'Example Ltd, Example Person'); assert.equal(result.verified, false);
  assert.equal(result.risk, 95); assert.equal(result.spamReported, false);
});
test('unknown IPQS name is absent, not an identity', async () => {
  const api = providers({ IPQS_API_KEY: 'synthetic' }, async () => new Response(JSON.stringify({ success: true, name: 'N/A' })));
  assert.equal((await api.ipqs(number)).name, null);
});
test('provider HTTP errors and malformed JSON are failures', async () => {
  const error = providers({ IPQS_API_KEY: 'synthetic' }, async () => new Response('', { status: 429 }));
  await assert.rejects(error.ipqs(number));
  const malformed = providers({ IPQS_API_KEY: 'synthetic' }, async () => new Response('{'));
  await assert.rejects(malformed.ipqs(number));
});
test('normalizer never accepts extensions or arbitrary text', () => {
  assert.equal(international('+39 (02) 1234-5678'), number);
  assert.equal(international(number + ';ext=123'), null);
});
test('IPQS cache is scoped to authenticated device, not shared with another user', async t => {
  const second = 'another-synthetic-device-token-0123456789';
  let calls = 0;
  const call = await fixture(t, { ipqs: async () => ({ name: String(++calls), ttlSeconds: 60 }) }, { tokens: [token, second] });
  assert.equal((await call(request('ipqs'))).body.name, '1');
  assert.equal((await call(request('ipqs'), `Bearer ${second}`)).body.name, '2');
  assert.equal((await call(request('ipqs'))).body.name, '1');
});
test('adapter supplies a bounded timeout and does not retry', async () => {
  let calls = 0;
  const api = providers({ IPQS_API_KEY: 'synthetic' }, async (_url, options) => {
    calls++;
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.redirect, 'error');
    throw new DOMException('Synthetic timeout', 'TimeoutError');
  });
  await assert.rejects(api.ipqs(number));
  assert.equal(calls, 1);
});
