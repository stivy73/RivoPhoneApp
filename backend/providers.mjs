// Only official APIs. Never return upstream payloads, credentials or error URLs.
export function international(value) {
  const normalized = typeof value === 'string' ? value.replace(/[\s().-]/g, '') : '';
  return /^\+[1-9]\d{7,14}$/.test(normalized) ? normalized : null;
}
function name(value) {
  return typeof value === 'string' && !['N/A', 'unknown', 'null', ''].includes(value.trim())
    ? value.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 160) : null;
}
async function json(fetcher, url, options) {
  const response = await fetcher(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(3500) });
  if (!response.ok) throw new Error('provider_unavailable');
  const body = await response.text();
  if (body.length > 262144) throw new Error('provider_response_too_large');
  return JSON.parse(body);
}
export function providers(env = process.env, fetcher = fetch) {
  return {
    google: env.GOOGLE_PLACES_API_KEY ? async number => {
      const data = await json(fetcher, 'https://places.googleapis.com/v1/places:searchText', {
        method: 'POST', headers: { 'Content-Type': 'application/json',
          'X-Goog-Api-Key': env.GOOGLE_PLACES_API_KEY,
          'X-Goog-FieldMask': 'places.displayName,places.internationalPhoneNumber,places.attributions,places.googleMapsUri' },
        body: JSON.stringify({ textQuery: number, languageCode: 'it', pageSize: 5 })
      });
      const place = data.places?.find(p => international(p.internationalPhoneNumber) === number);
      return { name: name(place?.displayName?.text), verified: !!place,
        attribution: 'Google Maps', attributions: place?.attributions || [],
        url: place?.googleMapsUri || '', ttlSeconds: 0 };
    } : null,
    ipqs: env.IPQS_API_KEY ? async number => {
      const data = await json(fetcher,
        `https://www.ipqualityscore.com/api/json/phone/${encodeURIComponent(env.IPQS_API_KEY)}/${encodeURIComponent(number)}`, {});
      if (data.success !== true) throw new Error('provider_unavailable');
      return { name: name(data.name), verified: false, spamReported: data.spammer === true,
        risk: Number.isInteger(data.fraud_score) ? Math.max(0, Math.min(100, data.fraud_score)) : null,
        attribution: 'IPQualityScore', ttlSeconds: Math.min(86400, Math.max(0, Number(env.IPQS_CACHE_SECONDS || 3600))) };
    } : null
  };
}
