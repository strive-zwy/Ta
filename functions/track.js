// Cloudflare Pages Function: /track
// 反代统计 Worker 的 /track 上报接口，规避 *.workers.dev 在国内访问不稳定的问题。

const WORKER = 'https://ta-stats.1228304424.workers.dev';

export async function onRequest(ctx) {
  const { request } = ctx;
  try {
    const body = await request.text();
    const resp = await fetch(WORKER + '/track', {
      method: 'POST',
      body: body,
      headers: {
        'Content-Type': 'text/plain;charset=UTF-8',
        'User-Agent': 'ta-pages-stats',
      },
    });
    // 透传 Worker 响应（包含 CORS 头），客户端不再关心内容
    const out = await resp.text();
    return new Response(out, {
      status: resp.status,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'no-store',
      },
    });
  } catch (e) {
    return new Response('', { status: 204 });
  }
}
