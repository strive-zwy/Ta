// Cloudflare Pages Function: /stats
// 反代统计 Worker 的 /stats 接口，规避 *.workers.dev 在国内访问不稳定的问题。

const WORKER = 'https://ta-stats.1228304424.workers.dev';

export async function onRequest(ctx) {
  try {
    const resp = await fetch(WORKER + '/stats', {
      headers: { 'User-Agent': 'ta-pages-stats' },
    });
    if (!resp.ok) throw new Error('worker ' + resp.status);
    const body = await resp.text();
    return new Response(body, {
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'no-store',
      },
    });
  } catch (e) {
    return new Response('{"pv":null,"dl":null}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'no-store',
      },
    });
  }
}
