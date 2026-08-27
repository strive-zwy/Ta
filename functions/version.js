// Cloudflare Pages Function: /version
// 返回最新 release 版本号 JSON。
// 不走 api.github.com（共享出口 IP 易被限流），改用 releases/latest 的 302 跳转解析 tag。

const REPO = 'strive-zwy/Ta';
const LATEST_URL = 'https://github.com/' + REPO + '/releases/latest';

async function getLatestTag() {
  const resp = await fetch(LATEST_URL, {
    redirect: 'manual',
    headers: { 'User-Agent': 'ta-pages-dl' },
  });
  // releases/latest 会 302 到 /releases/tag/<tag>
  const loc = resp.headers.get('Location') || resp.headers.get('location');
  if (!loc) return null;
  const m = loc.match(/\/releases\/tag\/([^/?#]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

export async function onRequest(ctx) {
  try {
    const tag = await getLatestTag();
    const version = tag ? tag.replace(/^v/, '') : null;
    return json({ version: version, tag: tag });
  } catch (e) {
    return json({ version: null, tag: null });
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
