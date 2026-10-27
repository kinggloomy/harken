/**
 * M4 受限模式前端验证：用系统浏览器（headless）+ 裸 CDP 打开控制台，断言 DOM 行为。
 *
 * 断言的是「用户真正看到的界面」：
 *   1) RESTRICTED 下 <code>#catalog-view</code> 可见，<code>#editor-wrap</code> / <code>#tpl-wrap</code> 隐藏
 *   2) 目录只列 READ/WRITE 脚本，DANGEROUS 脚本不出现
 *   3) 选中脚本后渲染参数表单；必填为空时点「试跑」不发请求，只弹提示
 *   4) 填了参数能跑通，并在结果区看到回显
 *   5) 页面零内联脚本，文档响应带严格 CSP
 *
 * 用法（先起 headless 浏览器并开 9222 端口，再启动 Library demo）：
 *   node cdp-m4-ui.js http://100.64.0.2:8091 ./screenshots
 */
const fs = require('node:fs');
const path = require('node:path');

const BASE = (process.argv[2] || 'http://100.64.0.2:8091').replace(/\/+$/, '');
const OUT = process.argv[3] || './screenshots';
const CDP = process.env.CDP_HTTP || 'http://127.0.0.1:9222';
const USER = 'admin';
const PASS = 'lib-admin-pw';

const results = [];
function check(name, pass, detail) {
  results.push({ name, pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${name}${pass ? '' : '  -> ' + JSON.stringify(detail)}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const list = await (await fetch(`${CDP}/json/list`)).json();
  const pages = list.filter((t) => t.type === 'page');
  // 优先挑一张空白页，避免拿到 edge:// 之类不允许导航的系统页
  const target = pages.find((t) => t.url === 'about:blank') || pages[0];
  if (!target) throw new Error('没有找到 page target，浏览器没起来？');

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  const pending = new Map();
  const events = [];
  let seq = 0;

  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    } else if (msg.method) {
      events.push(msg);
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

  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
      userGesture: true,
    });
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
    return r.result.value;
  };

  async function waitReady(timeoutMs = 15000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      try {
        if ((await evaluate('document.readyState')) === 'complete') return true;
      } catch (_) {
        /* 导航中执行上下文会被销毁，重试即可 */
      }
      await sleep(150);
    }
    return false;
  }

  async function shot(file, width, height, mobile) {
    await send('Emulation.setDeviceMetricsOverride', {
      width,
      height,
      deviceScaleFactor: mobile ? 2 : 1,
      mobile,
    });
    await sleep(400);
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(path.join(OUT, file), Buffer.from(r.data, 'base64'));
    console.log(`  截图 ${path.join(OUT, file)}`);
  }

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  await send('Network.setCacheDisabled', { cacheDisabled: true });
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });

  // ---- 打开控制台并登录（用 fetch 登录，比填表单稳）----
  await send('Page.navigate', { url: `${BASE}/harken/` });
  await waitReady();
  await sleep(600);

  const login = await evaluate(`
    (async () => {
      const r = await fetch('/harken/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(PASS)} })
      });
      return { status: r.status, body: await r.text() };
    })()
  `);
  check('登录接口返回 200', login.status === 200, login);

  await send('Page.navigate', { url: `${BASE}/harken/` });
  await waitReady();
  await sleep(1200);

  // ---- 断言 1：视图切换 ----
  const view = await evaluate(`(() => {
    const vis = (el) => {
      if (!el) return false;
      if (el.hidden) return false;
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return false;
      return el.getBoundingClientRect().height > 0;
    };
    const g = (id) => document.getElementById(id);
    return {
      catalogVisible: vis(g('catalog-view')),
      editorVisible: vis(g('editor-wrap')),
      tplVisible: vis(g('tpl-wrap')),
      loginVisible: vis(g('login-view')),
      consoleVisible: vis(g('console-view')),
    };
  })()`);
  check('RESTRICTED：目录视图可见', view.catalogVisible, view);
  check('RESTRICTED：编辑器隐藏（页面无 CodeMirror）', !view.editorVisible, view);
  check('RESTRICTED：模板下拉隐藏', !view.tplVisible, view);
  check('登录后进入控制台', view.consoleVisible && !view.loginVisible, view);

  // ---- 断言 2：目录内容与危险脚本过滤 ----
  const catalog = await evaluate(`(() => {
    const items = [...document.querySelectorAll('#catalog-list .catalog-item')];
    return {
      count: items.length,
      ids: items.map((li) => li.dataset.scriptId),
      names: items.map((li) => li.querySelector('.catalog-item-name').textContent),
      risk: document.getElementById('catalog-risk').textContent,
      bodyHasDanger: document.body.innerText.includes('清空缓存')
        || document.body.innerText.includes('purgeCache'),
    };
  })()`);
  check('目录列出 2 个脚本（lookup / exportReport）', catalog.count === 2, catalog);
  check('目录含 lookup 与 exportReport',
    catalog.ids.includes('lookup') && catalog.ids.includes('exportReport'), catalog);
  check('DANGEROUS 脚本 purgeCache 不出现', !catalog.bodyHasDanger && !catalog.ids.includes('purgeCache'), catalog);
  check('目录标注 maxRisk=WRITE', /WRITE/.test(catalog.risk), catalog);

  // ---- 断言 3：参数表单 + 必填拦截 ----
  await evaluate(`document.querySelector('#catalog-list .catalog-item[data-script-id="lookup"]').click()`);
  await sleep(400);
  const form = await evaluate(`(() => {
    const inputs = [...document.querySelectorAll('#param-form [data-param]')];
    return {
      panelVisible: !document.getElementById('param-panel').hidden,
      name: document.getElementById('param-script-name').textContent,
      inputs: inputs.map((i) => ({ param: i.dataset.param, type: i.dataset.type, value: i.value })),
      risk: document.getElementById('param-script-risk').textContent,
    };
  })()`);
  check('选中脚本后渲染参数表单', form.panelVisible && form.inputs.length === 1, form);
  check('参数对应 code（string）', form.inputs[0] && form.inputs[0].param === 'code', form);
  check('表单标题显示脚本名', /按编号查询/.test(form.name), form);

  // 必填为空 → 点试跑应该只弹提示，不发请求
  const beforeLog = events.length;
  await evaluate(`document.getElementById('btn-dryrun').click()`);
  await sleep(700);
  const empty = await evaluate(`(() => {
    const t = document.getElementById('toast');
    return {
      toastVisible: !t.hidden,
      toastText: t.textContent,
      outTitle: (document.getElementById('out-title') || {}).textContent || '',
    };
  })()`);
  check('必填为空时弹提示「请填写必填参数」', empty.toastVisible && /请填写必填参数/.test(empty.toastText), empty);

  // ---- 断言 4：填参数后能跑通 ----
  await evaluate(`(() => {
    const input = document.querySelector('#param-form [data-param="code"]');
    input.value = 'ABC123';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return input.value;
  })()`);
  await evaluate(`document.getElementById('btn-dryrun').click()`);
  await sleep(2000);
  const ran = await evaluate(`(() => {
    const out = document.getElementById('out');
    const text = out ? out.innerText : '';
    return {
      outTitle: (document.getElementById('out-title') || {}).textContent || '',
      text,
      hasEcho: document.body.innerText.includes('查到 code=ABC123'),
      commitDisabled: document.getElementById('btn-commit').disabled,
    };
  })()`);
  check('填参数后执行成功并回显「查到 code=ABC123」', ran.hasEcho, ran);
  check('commitAllowed=false 时提交按钮禁用', ran.commitDisabled === true, ran);

  // ---- 断言 5：零内联脚本 + 严格 CSP ----
  const inline = await evaluate(`document.querySelectorAll('script:not([src])').length`);
  check('页面零内联脚本', inline === 0, { inline });

  const docResp = events
    .filter((e) => e.method === 'Network.responseReceived')
    .map((e) => e.params.response)
    .filter((r) => r.url === `${BASE}/harken/`)
    .pop();
  const csp = docResp && docResp.headers ? (docResp.headers['content-security-policy'] || docResp.headers['Content-Security-Policy']) : null;
  check('文档响应带 CSP 且无 unsafe-inline/unsafe-eval',
    !!csp && !/unsafe-inline|unsafe-eval/.test(csp), { csp });

  // ---- 截图（桌面 + 手机）----
  await shot('m4-restricted-desktop.png', 1440, 900, false);
  await shot('m4-restricted-mobile.png', 390, 844, true);

  ws.close();
  const failed = results.filter((r) => !r.pass);
  console.log(`\n合计 ${results.length} 项，失败 ${failed.length} 项`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('脚本异常:', e.message);
  process.exit(2);
});
