/** 快速探针：确认 RESTRICTED 下隐藏元素的计算样式，以及窄屏有没有横向溢出。 */
const CDP = process.env.CDP_HTTP || 'http://127.0.0.1:9222';
const BASE = (process.argv[2] || 'http://100.64.0.2:8091').replace(/\/+$/, '');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const list = await (await fetch(`${CDP}/json/list`)).json();
  const page = list.filter((t) => t.type === 'page').find((t) => t.url === 'about:blank') || list.find((t) => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  const pending = new Map();
  let seq = 0;
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
    }
  });
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true });
    ws.addEventListener('error', rej, { once: true });
  });
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++seq;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
  const ev = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
    return r.result.value;
  };

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url: `${BASE}/harken/` });
  await sleep(1500);
  await ev(`fetch('/harken/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',body:JSON.stringify({username:'admin',password:'lib-admin-pw'})})`);
  await send('Page.navigate', { url: `${BASE}/harken/` });
  await sleep(1800);

  for (const [w, h, mobile] of [[1440, 900, false], [390, 844, true]]) {
    await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: mobile ? 2 : 1, mobile });
    await sleep(500);
    const info = await ev(`(() => {
      const g = (id) => document.getElementById(id);
      const cs = (id) => { const e = g(id); if (!e) return 'MISSING'; const s = getComputedStyle(e); return s.display + '/' + s.visibility + '/h=' + Math.round(e.getBoundingClientRect().height); };
      return {
        viewport: window.innerWidth + 'x' + window.innerHeight,
        docScrollW: document.documentElement.scrollWidth,
        bodyScrollW: document.body.scrollWidth,
        overflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
        editorWrap: cs('editor-wrap'),
        tplWrap: cs('tpl-wrap'),
        catalogView: cs('catalog-view'),
        chipRow: (() => { const c = g('chips'); if (!c) return 'MISSING'; return { w: Math.round(c.getBoundingClientRect().width), sw: c.scrollWidth }; })(),
      };
    })()`);
    console.log(w + 'px ->', JSON.stringify(info));
  }
  ws.close();
}

main().catch((e) => {
  console.error('探针失败:', e.message);
  process.exit(1);
});
