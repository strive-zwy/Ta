// Cloudflare Pages Function: /dl
// 反代 GitHub Releases 最新版 APK，解决国内直连 GitHub 下载慢/失败的问题。
// 用户能打开官网（pages.dev）就能下载，由 Cloudflare 边缘节点高速拉取 GitHub 资产。

const REPO = 'strive-zwy/Ta';
const API_URL = 'https://api.github.com/repos/' + REPO + '/releases/latest';

async function getLatestReleaseJson(ctx) {
  const cache = caches.default;
  const cacheKey = new Request(API_URL, { headers: { 'User-Agent': 'ta-pages-dl' } });
  const cached = await cache.match(cacheKey);
  if (cached) return cached.json();
  const resp = await fetch(cacheKey);
  if (!resp.ok) throw new Error('GitHub API ' + resp.status);
  const body = await resp.text();
  const toCache = new Response(body, {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=300' },
  });
  ctx.waitUntil(cache.put(cacheKey, toCache));
  return JSON.parse(body);
}

export async function onRequest(ctx) {
  const { request } = ctx;
  try {
    const rel = await getLatestReleaseJson(ctx);
    const assets = rel.assets || [];
    // 优先 Ta-vX.X.X.apk，其次任意 .apk
    const apk =
      assets.find(function (a) { return /^Ta-v.+\.apk$/i.test(a.name); }) ||
      assets.find(function (a) { return /\.apk$/i.test(a.name); });
    if (!apk) {
      return new Response('No APK asset in latest release', { status: 404 });
    }
    const upstream = await fetch(apk.browser_download_url, {
      redirect: 'follow',
      headers: { 'User-Agent': 'ta-pages-dl' },
    });
    if (!upstream.ok || !upstream.body) {
      return new Response('Upstream download failed: ' + upstream.status, { status: 502 });
    }
    const headers = new Headers();
    headers.set('Content-Type', 'application/vnd.android.package-archive');
    const len = upstream.headers.get('Content-Length');
    if (len) headers.set('Content-Length', len);
    headers.set('Content-Disposition', 'attachment; filename="' + apk.name + '"');
    headers.set('Cache-Control', 'public, max-age=3600');
    return new Response(upstream.body, { status: 200, headers: headers });
  } catch (e) {
    return new Response('Download proxy error: ' + (e && e.message), { status: 502 });
  }
}
