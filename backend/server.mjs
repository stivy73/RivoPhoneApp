import http from 'node:http';
import { timingSafeEqual, createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { providers, international } from './providers.mjs';

export function createProxy({ tokens, adapters, limit = 30, now = Date.now }) {
  if (!tokens?.length || tokens.some(t => t.length < 32)) throw new Error('Set RIVO_DEVICE_TOKENS to random tokens of at least 32 characters');
  const hashes = tokens.map(t => createHash('sha256').update(t).digest());
  const rates = new Map(), pending = new Map(), cache = new Map();
  function auth(header = '') {
    const hash = createHash('sha256').update(header.startsWith('Bearer ') ? header.slice(7) : '').digest();
    return hashes.findIndex(h => timingSafeEqual(hash, h));
  }
  const server = http.createServer(async (req, res) => {
    const send = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
    const device = auth(req.headers.authorization);
    if (device < 0) return send(401, { error: 'unauthorized' });
    const window = Math.floor(now() / 60000), rate = rates.get(device);
    const count = rate?.window === window ? rate.count + 1 : 1;
    rates.set(device, { window, count });
    if (count > limit) return send(429, { error: 'rate_limited' });
    if (req.method === 'GET' && req.url === '/v1/status') return send(200, { providers: Object.keys(adapters).filter(k => adapters[k]) });
    if (req.method !== 'POST' || req.url !== '/v1/identify') return send(404, { error: 'not_found' });
    try {
      let body = '';
      for await (const chunk of req) { body += chunk; if (body.length > 4096) return send(413, { error: 'too_large' }); }
      const input = JSON.parse(body);
      const number = international(input.number);
      // A single explicitly selected provider: no implicit fallbacks, queued fanout or retry.
      if (!number || number !== input.number || !['google', 'ipqs'].includes(input.provider) || input.enabled !== true)
        return send(400, { error: 'invalid_request' });
      const provider = input.provider;
      if (!adapters[provider]) return send(503, { error: 'not_configured' });
      const key = `${device}:${provider}:${number}`;
      for (const [k, v] of cache) if (v.expires <= now()) cache.delete(k);
      if (provider === 'ipqs' && input.refresh !== true && cache.has(key)) return send(200, cache.get(key).value);
      if (!pending.has(key)) {
        if (pending.size >= 16) return send(429, { error: 'busy' });
        const task = adapters[provider](number).then(result => {
          const value = { provider, number, ...result };
          if (provider === 'ipqs' && result.ttlSeconds > 0) {
            if (cache.size >= 1000) cache.delete(cache.keys().next().value);
            cache.set(key, { value, expires: now() + Math.min(86400, result.ttlSeconds) * 1000 });
          }
          return value;
        }).finally(() => pending.delete(key));
        pending.set(key, task);
      }
      return send(200, await pending.get(key));
    } catch { return send(502, { error: 'lookup_failed' }); }
  });
  server.requestTimeout = 8000;
  server.headersTimeout = 5000;
  server.timeout = 8000;
  return server;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createProxy({ tokens: (process.env.RIVO_DEVICE_TOKENS || '').split(',').filter(Boolean), adapters: providers() })
    .listen(Number(process.env.PORT || 8080), process.env.HOST || '127.0.0.1');
}
