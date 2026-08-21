// Ta 官网统计 Worker —— 隐私友好埋点
// 只记录聚合计数（PV / UV / 下载 / 国家维度聚合），不存储 IP、不做用户画像。
// KV 键设计：
//   pv:total / pv:YYYYMMDD   页面浏览（总 / 按日）
//   dl:total / dl:YYYYMMDD   APK 下载点击（总 / 按日）
//   uv:total                 独立访客（客户端 localStorage 首访标记）
//   country:XX               国家维度聚合计数（来自 Cloudflare 边缘，无 IP 落盘）

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Max-Age': '86400',
};

// 北京时间的日期键，与作息统计习惯一致
function dayKey() {
  return new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10).replace(/-/g, '');
}

async function bump(kv, key) {
  const cur = parseInt((await kv.get(key)) || '0', 10);
  await kv.put(key, String(cur + 1));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS });
    }

    if (url.pathname === '/track' && request.method === 'POST') {
      try {
        const body = await request.json();
        const type = body.type === 'download' ? 'dl' : 'pv';
        const day = dayKey();
        const jobs = [
          bump(env.STATS, `${type}:total`),
          bump(env.STATS, `${type}:${day}`),
        ];
        if (type === 'pv' && body.isNewVisitor) jobs.push(bump(env.STATS, 'uv:total'));
        const country = request.cf && request.cf.country;
        if (country) jobs.push(bump(env.STATS, `country:${country}`));
        await Promise.all(jobs);
      } catch (e) {
        console.error('track error:', e && (e.stack || e.message || String(e)));
      }
      return new Response(null, { status: 204, headers: CORS });
    }

    if (url.pathname === '/stats' && request.method === 'GET') {
      const day = dayKey();
      const keys = ['pv:total', 'uv:total', 'dl:total', `pv:${day}`, `dl:${day}`];
      const values = await Promise.all(keys.map((k) => env.STATS.get(k)));
      const countryList = await env.STATS.list({ prefix: 'country:' });
      const countries = {};
      await Promise.all(
        countryList.keys.map(async (k) => {
          countries[k.name.slice(8)] = parseInt((await env.STATS.get(k.name)) || '0', 10);
        })
      );
      const payload = {
        pv: parseInt(values[0] || '0', 10),
        uv: parseInt(values[1] || '0', 10),
        dl: parseInt(values[2] || '0', 10),
        today: {
          pv: parseInt(values[3] || '0', 10),
          dl: parseInt(values[4] || '0', 10),
        },
        countries,
      };
      return new Response(JSON.stringify(payload), {
        headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8' },
      });
    }

    return new Response('Not Found', { status: 404, headers: CORS });
  },
};
