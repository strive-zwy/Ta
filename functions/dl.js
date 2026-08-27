// Cloudflare Pages Function: /dl
// 反代 GitHub Releases 最新版 APK，解决国内直连 GitHub 下载慢/失败的问题。
// 不走 api.github.com（共享出口 IP 易被限流），用 releases/latest 302 解析 tag 后直接拼接下载地址。

const REPO = 'strive-zwy/Ta';
const LATEST_URL = 'https://github.com/' + REPO + '/releases/latest';

async function getLatestTag() {
  const resp = await fetch(LATEST_URL, {
    redirect: 'manual',
    headers: { 'User-Agent': 'ta-pages-dl' },
  });
  const loc = resp.headers.get('Location') || resp.headers.get('location');
  if (!loc) return null;
  const m = loc.match(/\/releases\/tag\/([^/?#]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

// 依据 release.yml 的命名规则生成候选资产路径：
// Ta-<tag>.apk（固定名副本）、ta-<tag>-app-release.apk（原始产物）
function candidateUrls(tag) {
  const base = 'https://github.com/' + REPO + '/releases/download/' + tag + '/';
  const names = ['Ta-' + tag + '.apk'];
  if (/^v/.test(tag)) {
    names.push('Ta-v' + tag.replace(/^v/, '') + '.apk');
    names.push('ta-' + tag + '-app-release.apk');
  }
  return names.map(function (n) { return base + n; });
}

export async function onRequest(ctx) {
  const { request } = ctx;
  try {
    const tag = await getLatestTag();
    if (!tag) {
      return new Response('Failed to resolve latest release tag', { status: 502 });
    }
    // 依次尝试候选地址
    let upstream = null, name = 'Ta-' + tag + '.apk';
    const urls = candidateUrls(tag);
    for (var i = 0; i < urls.length; i++) {
      const resp = await fetch(urls[i], {
        redirect: 'follow',
        headers: { 'User-Agent': 'ta-pages-dl' },
      });
      if (resp.ok && resp.body) {
        upstream = resp;
        name = urls[i].split('/').pop();
        break;
      }
    }
    if (!upstream) {
      return new Response('No APK asset found for tag ' + tag, { status: 404 });
    }
    const headers = new Headers();
    headers.set('Content-Type', 'application/vnd.android.package-archive');
    const len = upstream.headers.get('Content-Length');
    if (len) headers.set('Content-Length', len);
    headers.set('Content-Disposition', 'attachment; filename="' + name + '"');
    headers.set('Cache-Control', 'public, max-age=3600');
    return new Response(upstream.body, { status: 200, headers: headers });
  } catch (e) {
    return new Response('Download proxy error: ' + (e && e.message), { status: 502 });
  }
}
