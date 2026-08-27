// Cloudflare Pages Function: /version
// 返回最新 release 版本号 JSON（走自身域名，规避国内访问 api.github.com 慢的问题）。

const REPO = 'strive-zwy/Ta';
const API_URL = 'https://api.github.com/repos/' + REPO + '/releases/latest';

export async function onRequest(ctx) {
  const cache = caches.default;
  const cacheKey = new Request(API_URL, { headers: { 'User-Agent': 'ta-pages-dl' } });
  try {
    const cached = await cache.match(cacheKey);
    const data = cached ? await cached.json() : null;
    if (data && data.tag_name) {
      return json({ version: data.tag_name.replace(/^v?/, '') });
    }
    const resp = await fetch(cacheKey);
    if (!resp.ok) throw new Error('GitHub API ' + resp.status);
    const body = await resp.text();
    const toCache = new Response(body, {
      headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=300' },
    });
    ctx.waitUntil(cache.put(cacheKey, toCache));
    const rel = JSON.parse(body);
    return json({ version: (rel.tag_name || '').replace(/^v?/, '') });
  } catch (e) {
    return json({ version: null });
  }
}

function json(obj) {
  return new Response(JSON.stringify(obj), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'public, max-age=120',
    },
  });
}
