/* =============================================================================
 * Harken 控制台前端
 *
 * 设计前提：
 *   1. 页面与 API 同源，认证靠 HttpOnly Cookie（不用 localStorage 存令牌）。
 *   2. 一切「能不能写库」的判断都在服务端，前端只负责把限制<b>显示出来</b>，
 *      不靠隐藏按钮来保证安全。
 *   3. 不引入框架：这个页面要在没有网络的生产机上跑，任何 CDN、任何构建步骤
 *      都是新的失败点。CodeMirror 是随 jar 分发的本地副本。
 * ========================================================================== */
(function () {
  'use strict';

  // ---------------------------------------------------------------------------
  // 基础：基路径与元素句柄
  // ---------------------------------------------------------------------------

  /* 页面可能挂在 /harken、/harken/、/harken/index.html 三种形态下，
     基路径从地址栏推出来，不写死。 */
  var BASE = location.pathname
    .replace(/\/index\.html$/, '')
    .replace(/\/+$/, '');

  function $(id) { return document.getElementById(id); }

  var els = {
    loginView: $('login-view'),
    loginForm: $('login-form'),
    loginUser: $('login-user'),
    loginPass: $('login-pass'),
    loginSubmit: $('login-submit'),
    loginError: $('login-error'),

    consoleView: $('console-view'),
    chips: $('chips'),
    whoUser: $('who-user'),
    btnLogout: $('btn-logout'),
    banner: $('banner'),
    bannerTitle: $('banner-title'),
    bannerBody: $('banner-body'),

    tplSelect: $('tpl-select'),
    lint: $('lint'),
    runState: $('run-state'),
    btnDryrun: $('btn-dryrun'),
    btnCommit: $('btn-commit'),

    editor: $('editor'),
    confirmBar: $('confirm-bar'),
    confirmHead: $('confirm-head'),
    confirmBody: $('confirm-body'),
    confirmInput: $('confirm-input'),
    btnConfirmCommit: $('btn-confirm-commit'),
    btnConfirmCancel: $('btn-confirm-cancel'),

    outTitle: $('out-title'),
    outMeta: $('out-meta'),
    out: $('out'),
    btnClear: $('btn-clear'),

    history: $('history'),
    historyEmpty: $('history-empty'),
    btnHistory: $('btn-history'),

    toast: $('toast'),

    editorWrap: $('editor-wrap'),
    tplWrap: $('tpl-wrap'),

    catalogView: $('catalog-view'),
    catalogList: $('catalog-list'),
    catalogEmpty: $('catalog-empty'),
    catalogRisk: $('catalog-risk'),
    paramPanel: $('param-panel'),
    paramForm: $('param-form'),
    paramScriptName: $('param-script-name'),
    paramScriptRisk: $('param-script-risk'),
    paramScriptDesc: $('param-script-desc'),
    paramHelp: $('param-help')
  };

  var DRAFT_KEY = 'harken.draft.v1';
  var HISTORY_KEY = 'harken.history.v1';
  var HISTORY_MAX = 50;

  var state = {
    cm: null,
    me: null,
    health: null,
    index: null,
    busy: false,
    lastRun: null,       // {hash, ok, affectedRows, committed}
    compileTimer: null,
    dangerTimer: null,
    toastTimer: null,
    marks: { err: [], danger: [] },
    lint: { compileText: '', compileTone: '', danger: '' },

    // 受限模式（RESTRICTED）相关
    restricted: false,
    catalogDefs: {},        // id -> 受限模式用的定义（不含 script 源码）
    catalogScripts: {},     // id -> 完整定义（含 script，仅 FULL 模式作模板插入用）
    selectedScriptId: null,
    selectedDef: null
  };

  // ---------------------------------------------------------------------------
  // 小工具
  // ---------------------------------------------------------------------------

  function node(tag, cls, text) {
    var el = document.createElement(tag);
    if (cls) { el.className = cls; }
    if (text !== undefined && text !== null) { el.textContent = text; }
    return el;
  }

  function toast(message, isError) {
    els.toast.textContent = message;
    els.toast.className = isError ? 'toast is-error' : 'toast';
    els.toast.hidden = false;
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(function () { els.toast.hidden = true; }, 3200);
  }

  function hash(text) {
    var h = 5381;
    for (var i = 0; i < text.length; i++) {
      h = ((h << 5) + h + text.charCodeAt(i)) | 0;
    }
    return h;
  }

  function simpleName(typeName) {
    if (!typeName) { return ''; }
    var cut = typeName.lastIndexOf('.');
    return cut >= 0 ? typeName.substring(cut + 1) : typeName;
  }

  function clockOf(iso) {
    if (!iso) { return '--:--:--'; }
    var d = new Date(iso);
    if (isNaN(d.getTime())) { return '--:--:--'; }
    function p2(n) { return n < 10 ? '0' + n : '' + n; }
    return p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds());
  }

  function errText(res) {
    var d = res.data || {};
    if (d.error) { return d.error; }
    if (d.raw) { return String(d.raw).substring(0, 200); }
    return 'HTTP ' + res.status;
  }

  // ---------------------------------------------------------------------------
  // 主题
  //
  // 只改 <html data-theme>，一个具体颜色都不碰 —— 两套色值都写在 app.css 里，
  // JS 只知道「现在是深色还是浅色」。这样加第三套主题不用动这个文件。
  // ---------------------------------------------------------------------------

  var THEME_KEY = 'harken.theme.v1';

  function themeOf() {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  }

  function applyTheme(theme) {
    if (theme === 'light') {
      document.documentElement.setAttribute('data-theme', 'light');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
    try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* 存储不可用不影响本次切换 */ }

    // 按钮图标指的是「点了会变成什么」，不是「现在是什么」：
    // 深色下显示太阳（点了变亮），浅色下显示月亮。两个视图共用一套逻辑。
    var ico = theme === 'light' ? '☾' : '☀';
    var label = theme === 'light' ? '切换到深色主题' : '切换到浅色主题';
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].textContent = ico;
      buttons[i].title = label;
      buttons[i].setAttribute('aria-label', label);
    }

    // 换主题会连带改 color-scheme，滚动条从细变粗再由 CodeMirror 重新分配宽度，
    // 不重新量一次的话行号区宽度会停在旧值上，光标与文本会错位。
    if (state.cm) { state.cm.refresh(); }
  }

  function initTheme() {
    applyTheme(themeOf());
    var buttons = document.querySelectorAll('[data-theme-toggle]');
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].addEventListener('click', function () {
        applyTheme(themeOf() === 'light' ? 'dark' : 'light');
      });
    }
  }

  // ---------------------------------------------------------------------------
  // HTTP
  // ---------------------------------------------------------------------------

  async function api(path, options) {
    options = options || {};
    var init = {
      method: options.method || 'GET',
      credentials: 'same-origin',
      headers: {}
    };
    if (options.body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(options.body);
    }
    var resp = await fetch(BASE + path, init);
    var text = await resp.text();
    var data = {};
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = { raw: text }; }
    }
    return { status: resp.status, ok: resp.ok, data: data };
  }

  /** 会话失效的统一处理：回到登录页，而不是把 401 当成业务错误渲染。 */
  function sessionLost(res) {
    if (res.status !== 401) { return false; }
    showLogin('会话已过期，请重新登录。');
    return true;
  }

  // ---------------------------------------------------------------------------
  // 登录 / 登出
  // ---------------------------------------------------------------------------

  function showLogin(message) {
    els.consoleView.hidden = true;
    els.loginView.hidden = false;
    if (message) {
      els.loginError.textContent = message;
      els.loginError.hidden = false;
    } else {
      els.loginError.hidden = true;
    }
    if (state.cm) { state.cm.refresh(); }
    els.loginUser.focus();
  }

  function loginError(message) {
    els.loginError.textContent = message;
    els.loginError.hidden = false;
  }

  els.loginForm.addEventListener('submit', async function (event) {
    event.preventDefault();
    var username = els.loginUser.value.trim();
    var password = els.loginPass.value;
    if (!username || !password) {
      loginError('用户名和密码都要填。');
      return;
    }
    els.loginSubmit.disabled = true;
    els.loginError.hidden = true;
    try {
      var res = await api('/auth/login', { method: 'POST', body: { username: username, password: password } });
      if (res.ok) {
        els.loginPass.value = '';
        enterConsole(res.data);
        return;
      }
      if (res.status === 429) {
        loginError(errText(res) + '（' + (res.data.retryAfterSeconds || '') + '）');
      } else {
        loginError(errText(res));
      }
    } catch (e) {
      loginError('连不上服务端：' + e.message);
    } finally {
      els.loginSubmit.disabled = false;
    }
  });

  els.btnLogout.addEventListener('click', async function () {
    try { await api('/auth/logout', { method: 'POST', body: {} }); } catch (e) { /* 忽略 */ }
    // 整页重载而不是就地重置：编辑器实例、定时器、已注册的 CodeMirror 插件
    // 全都绑在这一次页面生命周期上，逐个拆容易漏一个，漏掉的那个会在
    // 下次登录后对着旧的 DOM 操作。重载是最省事的「干净状态」。
    location.reload();
  });

  function enterConsole(me) {
    state.me = me;
    els.loginView.hidden = true;
    els.consoleView.hidden = false;
    els.whoUser.textContent = '';
    els.whoUser.appendChild(node('b', null, me.username));
    els.whoUser.appendChild(document.createTextNode(' · ' + me.role));
    loadConsole();
  }

  // ---------------------------------------------------------------------------
  // 状态条与横幅
  // ---------------------------------------------------------------------------

  function chip(key, value, tone) {
    var el = node('span', 'chip' + (tone ? ' is-' + tone : ''));
    el.appendChild(node('span', 'chip-key', key));
    el.appendChild(node('span', 'chip-value', value));
    el.title = key + '：' + value;
    return el;
  }

  function renderChips() {
    var h = state.health;
    els.chips.textContent = '';

    var env = h.environment && h.environment.length ? h.environment : '未声明';
    els.chips.appendChild(chip('环境', env, h.environment ? '' : 'muted'));

    var txOk = h.transaction && h.transaction.supported;
    els.chips.appendChild(chip('事务', txOk ? '可回滚' : '无事务', txOk ? 'ok' : 'danger'));

    els.chips.appendChild(chip('角色', h.role, h.canCommit ? 'ok' : 'warn'));

    var guard = (h.writeGuard && h.writeGuard.description) || 'none';
    var guardTone = /^none/.test(guard) ? 'warn' : 'ok';
    els.chips.appendChild(chip('写入守卫', guard, guardTone));

    if (h.audit) {
      els.chips.appendChild(chip('审计', h.audit.stores || '-', h.audit.queryable ? 'ok' : 'warn'));
    }
    if (h.timeoutMs) {
      els.chips.appendChild(chip('超时', (h.timeoutMs / 1000) + 's', ''));
    }
    els.chips.appendChild(chip('版本', h.version || '?', 'muted'));
  }

  function renderBanner() {
    var h = state.health;
    if (!h.transaction || !h.transaction.supported) {
      els.banner.className = 'banner';
      els.bannerTitle.textContent = '无事务保护';
      els.bannerBody.textContent =
        '当前环境没有事务管理器：试跑会被拒绝，脚本一旦执行就直接落库，无法回滚。'
        + '这是配置缺陷，请先修好再用。';
      els.banner.hidden = false;
      return;
    }
    if (!h.canCommit) {
      els.banner.className = 'banner is-warn';
      els.bannerTitle.textContent = '只读角色';
      els.bannerBody.textContent =
        '你的角色是 ' + h.role + '，只能试跑（改动会回滚）。需要落库请找管理员提权到 ADMIN。';
      els.banner.hidden = false;
      return;
    }
    els.banner.hidden = true;
  }

  // ---------------------------------------------------------------------------
  // 元数据索引（补全的数据源）
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // 补全索引
  //
  // 服务端只给「名字」：类名按包分组 + Bean 名到类型。方法表按需拉 ——
  // 一个中等项目几百个类，把方法全量塞进首屏是几百 KB 的无用传输。
  // ---------------------------------------------------------------------------

  function buildIndex(meta) {
    var beanTypes = (meta && meta.beanTypes) || {};
    var byPackage = (meta && meta.byPackage) || {};
    var byName = new Map();
    var byType = new Map();
    // 简名 → 全部同名的全限定名。多包同名时一个都不能丢：丢了就只能替用户猜，
    // 而猜错的后果是补全给出另一个类的方法
    var fqcnList = {};

    Object.keys(beanTypes).forEach(function (name) {
      var bean = { name: name, type: beanTypes[name] };
      byName.set(name, bean);
      var simple = simpleName(bean.type);
      // 一个类型有多个实例时，按 bean 名补全才是可靠的那条路
      if (!byType.has(simple)) { byType.set(simple, bean); }
    });

    Object.keys(byPackage).forEach(function (pkg) {
      (byPackage[pkg] || []).forEach(function (simple) {
        (fqcnList[simple] = fqcnList[simple] || []).push(pkg + '.' + simple);
      });
    });

    state.index = {
      byName: byName,
      byType: byType,
      byPackage: byPackage,
      fqcnList: fqcnList,
      imports: {},                                  // 类简名 → 全限定名，来自脚本里的单类型 import
      stars: [],                                    // 星号 import 过的包名
      // 方法表分两张，都按类型全名存：静态成员（UUID.randomUUID）与实例成员
      // （uuid.toString）内容不同，混在一张表里必然有一边是错的
      methods: { type: new Map(), inst: new Map() }
    };
    refreshImports();
  }

  var KEYWORDS = [
    'def', 'if', 'else', 'for', 'while', 'return', 'try', 'catch', 'finally',
    'throw', 'new', 'null', 'true', 'false', 'this', 'it', 'println', 'assert',
    'switch', 'case', 'break', 'continue', 'instanceof', 'in'
  ];

  /**
   * 显式类型变量：在脚本正文里找 `Type name =`。
   *
   * 只认大写开头的类型名，是刻意的：小写开头在这个上下文里几乎都是 def 推断，
   * 而推断出来的类型得靠执行才知道，不猜。
   *
   * @return 写出来的类型名（可能是简名，也可能是全限定名），找不到返回 null
   */
  function declaredTypeOf(name) {
    var text = state.cm.getValue();
    var re = new RegExp('\\b([A-Z][\\w.]*)\\s+' + name + '\\s*=');
    var m = re.exec(text);
    return m ? m[1] : null;
  }

  // ---------------------------------------------------------------------------
  // 表达式类型推导
  //
  // 补全只需要回答一句话：光标左边那个值是什么类型。回答了它，`list.`、
  // `list.get(0).`、`a.`（闭包参数）就是同一个问题。
  //
  // 只解析单行内的 a.b().c 链。跨行链要处理续行与缩进，而实际写法几乎都是单行；
  // 推不出来时宁可不弹 —— 弹错类型的方法比不弹难查得多。
  // ---------------------------------------------------------------------------

  function isIdentChar(c) {
    return /[A-Za-z0-9_$]/.test(c);
  }

  function skipSpaceBack(text, i) {
    while (i >= 0 && /\s/.test(text.charAt(i))) { i--; }
    return i;
  }

  /** 从右括号下标往前找配对的左括号；找不到返回 -1。字符串里的括号不区分。 */
  function matchParenBack(text, close) {
    var depth = 0;
    for (var i = close; i >= 0; i--) {
      var c = text.charAt(i);
      if (c === ')') {
        depth++;
      } else if (c === '(') {
        depth--;
        if (depth === 0) { return i; }
      }
    }
    return -1;
  }

  /**
   * 从 {@code end} 往前解析一条 {@code a.b().c} 链。
   *
   * @param text 要解析的文本（当前行到光标处，或整段脚本）
   * @param end  链尾之后的下标
   * @return {@code {segs, prefix}}，{@code segs} 从左到右、{@code call} 表示带括号；
   *         光标前不是「名字 + 点号」时返回 {@code null}
   */
  function parseChainAt(text, end) {
    var i = end - 1;
    while (i >= 0 && isIdentChar(text.charAt(i))) { i--; }
    var prefix = text.substring(i + 1, end);
    if (i < 0 || text.charAt(i) !== '.') { return null; }

    var segs = [];
    var p = i - 1;
    while (true) {
      p = skipSpaceBack(text, p);
      if (p < 0) { return null; }
      if (text.charAt(p) === ')') {
        var open = matchParenBack(text, p);
        if (open < 0) { return null; }
        p = skipSpaceBack(text, open - 1);
        var callEnd = p + 1;
        while (p >= 0 && isIdentChar(text.charAt(p))) { p--; }
        var callName = text.substring(p + 1, callEnd);
        if (!callName) { return null; }
        segs.unshift({ name: callName, call: true });
      } else if (isIdentChar(text.charAt(p))) {
        var idEnd = p + 1;
        while (p >= 0 && isIdentChar(text.charAt(p))) { p--; }
        segs.unshift({ name: text.substring(p + 1, idEnd), call: false });
      } else {
        return null;
      }
      p = skipSpaceBack(text, p);
      if (p < 0 || text.charAt(p) !== '.') { break; }
      p--;
      // Groovy 的 a?.b：点号前面还有一个问号
      if (p >= 0 && text.charAt(p) === '?') { p--; }
    }
    return { segs: segs, prefix: prefix };
  }

  /** 拆 `java.util.Map<K, V>` → {raw, args}。只拆一层，嵌套参数原样留在字符串里。 */
  function splitGeneric(type) {
    var text = type || '';
    var open = text.indexOf('<');
    if (open < 0 || text.charAt(text.length - 1) !== '>') {
      return { raw: text, args: [] };
    }
    var args = [];
    var depth = 0;
    var start = open + 1;
    for (var i = open; i < text.length; i++) {
      var c = text.charAt(i);
      if (c === '<') {
        depth++;
      } else if (c === '>') {
        depth--;
        if (depth === 0) {
          args.push(text.substring(start, i).trim());
          break;
        }
      } else if (c === ',' && depth === 1) {
        args.push(text.substring(start, i).trim());
        start = i + 1;
      }
    }
    return { raw: text.substring(0, open), args: args };
  }

  function rawType(type) {
    return splitGeneric(type).raw;
  }

  /**
   * 把返回类型里的类型变量换成实参：{@code List<Foo>} 上取 {@code get(int)} 声明的
   * {@code E}，换成 {@code Foo}。不换的话调用链从第二个方法起就断了。
   *
   * 按整词替换 —— {@code Map.Entry} 里的 {@code Entry} 不能被 {@code E} 误伤。
   */
  function substitute(returns, typeVars, args) {
    if (!returns || !typeVars || !typeVars.length || !args || !args.length) {
      return returns;
    }
    var out = returns;
    for (var i = 0; i < typeVars.length && i < args.length; i++) {
      out = out.replace(new RegExp('\\b' + typeVars[i] + '\\b', 'g'), args[i]);
    }
    return out;
  }

  /**
   * 集合类的元素类型。
   *
   * 用白名单而不是「有泛型参数就算集合」：{@code Map<K,V>} 也有泛型参数，但它的两个
   * 参数是键和值，当成元素会把闭包第一个参数推成 K 却列 V 的成员。
   */
  var ELEMENT_CONTAINERS = [
    'java.util.List', 'java.util.Collection', 'java.util.Set', 'java.util.Iterable',
    'java.util.Queue', 'java.util.Deque', 'java.util.ArrayList', 'java.util.LinkedList',
    'java.util.HashSet', 'java.util.LinkedHashSet', 'java.util.Optional',
    'java.util.stream.Stream', 'java.util.stream.BaseStream'
  ];

  function elementTypeOf(type) {
    var g = splitGeneric(type);
    if (g.args.length === 1 && ELEMENT_CONTAINERS.indexOf(g.raw) >= 0) {
      return g.args[0];
    }
    return /\[\]$/.test(g.raw) ? g.raw.substring(0, g.raw.length - 2) : null;
  }

  function mapPairOf(type) {
    var g = splitGeneric(type);
    return g.raw === 'java.util.Map' && g.args.length === 2
      ? { key: g.args[0], value: g.args[1] } : null;
  }

  /** 简名 → 全限定名。import 优先；索引里唯一才认，多包同名不猜。 */
  function fqcnOfSimple(name) {
    if (!state.index || !name) { return null; }
    if (name.indexOf('.') >= 0) { return name; }
    if (state.index.imports[name]) { return state.index.imports[name]; }
    var list = state.index.fqcnList[name];
    return list && list.length === 1 ? list[0] : null;
  }

  /**
   * 一个名字在脚本里是什么类型。
   *
   * 五种来源，可靠性从高到低：闭包参数（就在眼前）、Bean 名、显式类型变量、
   * import 过的类名、索引里唯一的类名。
   *
   * @param scope 闭包参数名 → 类型；没有闭包上下文时传 null
   * @return {@code {type, isClass}}；{@code isClass} 表示这个名字是类本身而不是一个值
   */
  function nameType(name, scope) {
    if (!state.index || !name) { return null; }
    if (scope && scope[name]) { return { type: scope[name], isClass: false }; }
    if (state.index.byName.has(name)) {
      return { type: state.index.byName.get(name).type, isClass: false };
    }
    var declared = declaredTypeOf(name);
    if (declared) {
      var fromDecl = fqcnOfSimple(declared);
      if (fromDecl) { return { type: fromDecl, isClass: false }; }
    }
    if (state.index.imports[name]) {
      return { type: state.index.imports[name], isClass: true };
    }
    var list = state.index.fqcnList[name];
    return list && list.length === 1 ? { type: list[0], isClass: true } : null;
  }

  /** 一个值/类该查哪张方法表。 */
  function methodTarget(res) {
    return { kind: res.isClass ? 'type' : 'inst', owner: rawType(res.type) };
  }

  /** 方法表里找同名方法；重载取第一条（补全只用它的名字和返回类型）。 */
  function findMethod(methods, name) {
    for (var i = 0; i < methods.length; i++) {
      if (methods[i].name === name) { return methods[i]; }
    }
    return null;
  }

  /**
   * 沿链推类型，全程同步：只查已缓存的方法表，缺表就把缺的那一环交回去，
   * 由调用方去拉，拉回来再跑一次。
   *
   * @return {@code {type, isClass}}、{@code {missing}} 或 {@code null}（推不出来）
   */
  function chainType(segs, scope) {
    if (!segs || !segs.length || segs[0].call) { return null; }
    var cur = nameType(segs[0].name, scope);
    if (!cur) { return null; }
    for (var i = 1; i < segs.length; i++) {
      var known = cachedMethods(methodTarget(cur));
      if (!known) { return { missing: methodTarget(cur) }; }
      var method = findMethod(known.methods, segs[i].name);
      if (!method) { return null; }
      cur = {
        type: substitute(method.returns, known.typeVars, splitGeneric(cur.type).args),
        isClass: false
      };
    }
    return { type: cur.type, isClass: cur.isClass };
  }

  /**
   * 光标所在的最近一层闭包：接收者链、方法名、参数名。
   *
   * 闭包参数的类型只能从「接收者是什么集合」推 —— Groovy 的 each/collect 是运行时
   * 扩展方法，反射出来的方法表里根本没有它们的签名。
   *
   * @return {@code {segs, method, names}}，光标不在「带接收者的闭包」里时返回 null
   */
  function closureAt(cm) {
    var text = cm.getValue();
    var open = braceOf(text, cm.indexFromPos(cm.getCursor()));
    if (open < 0) { return null; }

    // `{ a, b ->` / `{ it ->` / `{ ->` / 无箭头（隐式 it）。
    // 只看开头 120 字符且不跨花括号：嵌套闭包里的箭头不该算到外层头上
    var head = text.substring(open + 1, open + 120);
    var arrow = head.indexOf('->');
    var names;
    var p;
    if (arrow >= 0 && head.substring(0, arrow).indexOf('{') < 0
            && head.substring(0, arrow).indexOf('}') < 0) {
      // Groovy 写法：参数在花括号里面，`{ a ->`
      names = paramNames(head.substring(0, arrow));
      p = skipSpaceBack(text, open - 1);
      if (p >= 0 && text.charAt(p) === ')') {
        var callParen = matchParenBack(text, p);
        if (callParen >= 0) { p = skipSpaceBack(text, callParen - 1); }
      }
    } else {
      var start = lambdaDeclStart(text, open);
      if (start < 0) {
        names = ['it'];
        p = skipSpaceBack(text, open - 1);
        if (p >= 0 && text.charAt(p) === ')') {
          var otherParen = matchParenBack(text, p);
          if (otherParen >= 0) { p = skipSpaceBack(text, otherParen - 1); }
        }
      } else {
        names = lambdaNames(text, start, open);
        p = skipSpaceBack(text, start - 1);
      }
    }
    var nameEnd = p + 1;
    while (p >= 0 && isIdentChar(text.charAt(p))) { p--; }
    var method = text.substring(p + 1, nameEnd);
    if (!method) { return null; }
    p = skipSpaceBack(text, p);
    // 只有 `xxx.each { }` 这种带接收者的写法能有元素类型；裸调用 `each { }` 没有
    if (p < 0 || text.charAt(p) !== '.') { return null; }
    var chain = parseChainAt(text, p + 1);
    return chain ? { segs: chain.segs, method: method, names: names } : null;
  }

  /** `{` 与光标之间最近的一层未闭合左花括号；没有返回 -1。 */
  function braceOf(text, pos) {
    var depth = 0;
    for (var i = pos - 1; i >= 0; i--) {
      var c = text.charAt(i);
      if (c === '}') {
        depth++;
      } else if (c === '{') {
        if (depth === 0) { return i; }
        depth--;
      }
    }
    return -1;
  }

  /** `(a, b)` / `a, b` / 空 → 参数名数组。带类型声明时只取最后一个词。 */
  function paramNames(text) {
    var plain = text.replace(/[()]/g, ' ').trim();
    if (!plain) { return []; }
    return plain.split(',').map(function (part) {
      var words = part.trim().split(/\s+/);
      return words[words.length - 1];
    }).filter(function (name) { return /^[A-Za-z_$][\w$]*$/.test(name); });
  }

  /**
   * `list.forEach(m -> {` 是 Java 风格的 lambda：箭头和参数都在花括号<b>前面</b>，
   * 与 Groovy 的 `{ it -> }` 正好相反。两种都得认 —— 用户两种都会写。
   *
   * @return 参数声明起点（`(` 或第一个参数名的首字符）；花括号前没有箭头时返回 -1
   */
  function lambdaDeclStart(text, open) {
    var p = skipSpaceBack(text, open - 1);
    if (p < 0 || text.charAt(p) !== '>') { return -1; }
    p = skipSpaceBack(text, p - 1);
    if (p < 0 || text.charAt(p) !== '-') { return -1; }
    p = skipSpaceBack(text, p - 1);
    if (text.charAt(p) === ')') {
      var pair = matchParenBack(text, p);
      return pair < 0 ? -1 : pair;
    }
    // 裸参数 `m` 或 `m, n`：退到最左边那个名字
    while (p >= 0) {
      while (p >= 0 && isIdentChar(text.charAt(p))) { p--; }
      p = skipSpaceBack(text, p);
      if (p >= 0 && text.charAt(p) === '(') { return p; }
      if (p < 0 || text.charAt(p) !== ',') { break; }
      p = skipSpaceBack(text, p - 1);
    }
    return p + 1;
  }

  /** 参数声明起点到箭头之间的参数名。 */
  function lambdaNames(text, start, open) {
    var p = skipSpaceBack(text, open - 1);
    p = skipSpaceBack(text, p - 1);
    return paramNames(text.substring(start, p));
  }

  /**
   * 光标所在闭包的参数名 → 类型。
   *
   * 参数类型就是接收者的元素类型：{@code List<CategoryView>.forEach(a -> ...)} 里
   * {@code a} 是 {@code CategoryView}。这覆盖绝大多数遍历写法，推不出就不给。
   */
  function closureParams(cm) {
    var closure = closureAt(cm);
    if (!closure || !closure.names.length) { return null; }
    var recv = chainType(closure.segs, null);
    if (!recv || !recv.type) { return null; }
    var pair = mapPairOf(recv.type);
    var element = pair ? null : elementTypeOf(recv.type);
    var result = {};
    for (var i = 0; i < closure.names.length; i++) {
      var type = null;
      if (pair) {
        type = i === 0 ? pair.key : i === 1 ? pair.value : null;
      } else if (i === 0) {
        type = element;
      } else if (i === 1 && closure.method === 'eachWithIndex') {
        type = 'java.lang.Integer';
      }
      if (type) { result[closure.names[i]] = type; }
    }
    return result;
  }

  /**
   * 一条方法候选。
   *
   * @param typeVars 类声明的类型参数名，用来把签名里的 {@code K} / {@code V} 换成实参
   * @param args     接收者带上来的泛型实参；没有就原样显示签名
   */
  function methodToHint(method, typeVars, args) {
    var params = (method.params || []).map(function (p) {
      return substitute(p.type, typeVars, args) + ' ' + p.name;
    }).join(', ');
    var zero = (method.params || []).length === 0;
    return {
      text: method.name + (zero ? '()' : '('),
      displayText: method.name + '(' + params + ') : ' + substitute(method.returns, typeVars, args),
      className: 'hint-method'
    };
  }

  function startsWithIgnoreCase(text, prefix) {
    return text.toLowerCase().lastIndexOf(prefix.toLowerCase(), 0) === 0;
  }

  /**
   * 成员补全的候选：链尾类型的成员，枚举类再加常量。
   *
   * <p>链推不出类型时返回空列表，不做「按名字猜一张方法表」的兜底 ——
   * 弹出另一个类的成员，用户点下去就是一条调不通的代码。
   */
  function memberHints(chain, scope) {
    var res = chainType(chain.segs, scope);
    if (!res || !res.type) { return []; }
    var known = cachedMethods(methodTarget(res));
    if (!known) { return []; }

    var prefix = chain.prefix;
    var list = [];
    // 枚举常量不是方法，但 `Hexagram.` 后面真正能用的就是它
    (known.constants || []).forEach(function (name) {
      if (startsWithIgnoreCase(name, prefix)) {
        list.push({ text: name, displayText: name + '（枚举常量）', className: 'hint-method' });
      }
    });
    (known.methods || []).forEach(function (method) {
      if (startsWithIgnoreCase(method.name, prefix)) {
        list.push(methodToHint(method, known.typeVars, splitGeneric(res.type).args));
      }
    });

    // 没 import 的类：上面这些方法现在一个都调不到，先摆一条「补 import」
    if (res.isClass && chain.segs.length === 1 && !state.index.imports[chain.segs[0].name]) {
      list.unshift(importHint(known, prefix));
    }
    return list;
  }

  /** CodeMirror 补全助手：同步返回 {list, from, to}。 */
  function hintSource(cm) {
    var cur = cm.getCursor();
    var before = cm.getLine(cur.line).substring(0, cur.ch);

    // 一、import 行：提示包名与类名
    var importLine = /^\s*import\s+(?:static\s+)?([\w$.]*)$/.exec(before);
    if (importLine) {
      var typed = importLine[1];
      var lastDot = typed.lastIndexOf('.');
      var pkg = lastDot >= 0 ? typed.substring(0, lastDot) : '';
      var partial = lastDot >= 0 ? typed.substring(lastDot + 1) : typed;
      return {
        list: importCandidates(pkg, partial),
        from: CodeMirror.Pos(cur.line, cur.ch - partial.length),
        to: cur
      };
    }

    // 二、成员补全：a.b().c 这条链尾上有什么
    var chain = parseChainAt(before, before.length);
    if (chain) {
      return {
        list: memberHints(chain, closureParams(cm)),
        from: CodeMirror.Pos(cur.line, cur.ch - chain.prefix.length),
        to: cur
      };
    }

    // 三、普通标识符：Bean 名 + 类型名 + 类简名 + 关键字
    var idRe = /([A-Za-z_$][\w$]*)$/;
    var idMatch = idRe.exec(before);
    var word = idMatch ? idMatch[1] : '';
    if (!word) { return { list: [], from: cur, to: cur }; }
    var from = CodeMirror.Pos(cur.line, cur.ch - word.length);

    // `new ` 后面只能是类：Bean 名和关键字在这个位置都是错的（`new orderService()` 编译不过）
    var afterNew = /\bnew\s+$/.test(before.substring(0, before.length - word.length));

    var list = [];
    if (state.index) {
      if (!afterNew) {
        state.index.byName.forEach(function (bean, name) {
          if (startsWithIgnoreCase(name, word)) {
            list.push({ text: name, displayText: name + ' : ' + bean.type, className: '' });
          }
        });
        state.index.byType.forEach(function (bean, simple) {
          if (startsWithIgnoreCase(simple, word)) {
            list.push({ text: simple, displayText: simple + '（类型，实例名 ' + bean.name + '）', className: 'hint-method' });
          }
        });
      }
      Object.keys(state.index.fqcnList).forEach(function (simple) {
        if (!startsWithIgnoreCase(simple, word)) { return; }
        state.index.fqcnList[simple].forEach(function (fqcn) {
          list.push(classHint(simple, fqcn));
        });
      });
    }
    if (!afterNew) {
      KEYWORDS.forEach(function (kw) {
        if (startsWithIgnoreCase(kw, word)) {
          list.push({ text: kw, displayText: kw + '（关键字）', className: 'hint-method' });
        }
      });
    }

    // 前缀完全匹配的排前面：`order` 不该被 `orderServiceXxx` 顶掉
    list.sort(function (a, b) {
      var aExact = a.text.toLowerCase() === word.toLowerCase() ? 0 : 1;
      var bExact = b.text.toLowerCase() === word.toLowerCase() ? 0 : 1;
      return aExact - bExact || a.text.length - b.text.length;
    });
    return { list: list.slice(0, 60), from: from, to: cur };
  }

  CodeMirror.registerHelper('hint', 'harken', hintSource);

  /**
   * import 补全的候选：子包 + 本级包里的类。
   *
   * <p>子包不能靠枚举目录拿：实测 fat jar 返回 2 个目录、exploded 返回 0 个，
   * 同一份代码两种结果。所以从包名表里反推 —— 纯字符串操作，零成本。
   */
  function importCandidates(pkg, partial) {
    var list = [];
    if (!state.index) { return list; }
    var seen = {};
    var prefix = pkg ? pkg + '.' : '';

    Object.keys(state.index.byPackage).forEach(function (full) {
      if (prefix && full.indexOf(prefix) !== 0) { return; }
      var rest = full.substring(prefix.length);
      var dot = rest.indexOf('.');
      var head = dot < 0 ? rest : rest.substring(0, dot);
      if (!head || seen[head] || !startsWithIgnoreCase(head, partial)) { return; }
      seen[head] = true;
      list.push({ text: head, displayText: head + '（包）', className: 'hint-method' });
    });

    (state.index.byPackage[pkg] || []).forEach(function (simple) {
      if (startsWithIgnoreCase(simple, partial)) {
        list.push({ text: simple, displayText: simple, className: '' });
      }
    });

    // 包在前、类在后，各自按名字有序；50 条足够挑，再多也看不过来
    return list.slice(0, 50);
  }

  // 单类型 import：`import a.b.C`。别名（`as X`）不认，那得额外解析语义
  var IMPORTS_RE = /^\s*import\s+(?!static\b)([\w$]+(?:\.[\w$]+)+)\s*$/gm;

  // 星号 import：`import a.b.*`。单独收，用来判断「已有通配就不必再补单类型 import」
  var STAR_IMPORTS_RE = /^\s*import\s+(?!static\b)([\w$]+(?:\.[\w$]+)*)\.\s*\*\s*$/gm;

  /**
   * 从脚本里抽出已 import 的「简名 → 全限定名」，以及星号 import 过的包名。
   *
   * <p>{@code import static} 不认：它的目标是成员不是类，混进来只会污染补全。
   */
  function refreshImports() {
    if (!state.index || !state.cm) { return; }
    var text = state.cm.getValue();
    var map = {};
    var m;
    IMPORTS_RE.lastIndex = 0;
    while ((m = IMPORTS_RE.exec(text))) {
      map[simpleName(m[1])] = m[1];
    }
    var stars = [];
    STAR_IMPORTS_RE.lastIndex = 0;
    while ((m = STAR_IMPORTS_RE.exec(text))) {
      stars.push(m[1]);
    }
    state.index.imports = map;
    state.index.stars = stars;
  }

  var importTimer = 0;

  /** 防抖刷新 import 表：每次击键都全量扫正文，脚本长了会卡。 */
  function scheduleImportRefresh() {
    if (!state.index) { return; }
    clearTimeout(importTimer);
    importTimer = setTimeout(refreshImports, 400);
  }

  /**
   * 把类 import 进脚本。三种情况不动手：
   *
   * <ul>
   *   <li>已经 import 了它 —— 没事可做。</li>
   *   <li>已有 {@code import a.b.*} 覆盖了它所在的包 —— 实测星号 import 同样能压过
   *       Groovy 默认导入，再补一行单类型 import 是多余的。</li>
   *   <li>同名类已经 import 了<b>另一个</b> —— 硬插会 {@code Duplicate import} 编译不过；
   *       而悄悄替换掉用户原来那个更糟。只提示，让用户自己决定。</li>
   * </ul>
   */
  function insertImport(cm, fqcn) {
    if (!fqcn || fqcn.indexOf('.') < 0) { return; }
    // 同上：插不插 import 全看这张表，读到旧表就会插出重复的一行
    refreshImports();
    var simple = simpleName(fqcn);
    var verdict = importState(simple, fqcn);
    if (verdict === 'ok') { return; }
    if (verdict === 'conflict') {
      toast(simple + ' 已 import 为 ' + state.index.imports[simple]
              + '，没有自动加 ' + fqcn + '，需要的话请手动改', true);
      return;
    }
    var at = importAnchor(cm);
    var lines = cm.lineCount();
    if (at < lines) {
      cm.replaceRange('import ' + fqcn + '\n', CodeMirror.Pos(at, 0), null, '+harken');
    } else {
      // 整个文件以 import 结尾：接在最后一行后面，而不是贴到它尾巴上
      cm.replaceRange('\nimport ' + fqcn,
              CodeMirror.Pos(lines - 1, cm.getLine(lines - 1).length), null, '+harken');
    }
    refreshImports();
  }

  /** 这个名字现在会不会绑到 fqcn：ok = 已经是；need = 要补一行；conflict = 被另一个同名类占了。 */
  function importState(simple, fqcn) {
    var imported = state.index.imports[simple];
    if (imported === fqcn) { return 'ok'; }
    if (imported) { return 'conflict'; }
    return state.index.stars.indexOf(fqcn.substring(0, fqcn.lastIndexOf('.'))) >= 0 ? 'ok' : 'need';
  }

  /** import 插在哪：接在最后一条 import 之后；一条都没有就跳过文件头的注释与空行。 */
  function importAnchor(cm) {
    var count = cm.lineCount();
    var last = -1;
    for (var i = 0; i < count; i++) {
      if (/^\s*import\s/.test(cm.getLine(i))) { last = i; }
    }
    if (last >= 0) { return last + 1; }
    var at = 0;
    while (at < count) {
      var text = cm.getLine(at).trim();
      var comment = text.indexOf('//') === 0 || text.indexOf('/*') === 0 || text.indexOf('*') === 0;
      if (text && !comment) { break; }
      at++;
    }
    return at;
  }

  /**
   * 选中一个类简名：先落字，再补 import。
   *
   * <p>两步都在 show-hint 的同一个 operation 里，所以一次 Ctrl+Z 能全撤回。
   */
  function pickClass(cm, data, simple, fqcn) {
    cm.replaceRange(simple, data.from, data.to, '+harken');
    insertImport(cm, fqcn);
  }

  /**
   * 「补 import」这条候选。
   *
   * <p>{@code text} 用当前已经敲完的前缀，点了等于不动文本 —— 只借这一下补 import。
   * 它之所以要混在方法列表里，是因为「没 import 的简名 + 点号」这个组合下，
   * 列出来的方法一个都调不到，得先把这件事说清楚。
   */
  function importHint(known, prefix) {
    return {
      text: prefix || '',
      displayText: '〔补 import ' + known.owner + '〕'
              + (known.shadowedBy ? ' 现在绑的是 ' + known.shadowedBy : ''),
      className: 'hint-import',
      hint: function (cm) { insertImport(cm, known.owner); }
    };
  }

  /**
   * 类简名候选。选中时把类名落下，并顺手补上 import。
   *
   * <p>同名类有几个就列几条，每条带自己的全限定名 —— 这样「导入的」永远是
   * 「你点的那一条」，不需要在这里替用户判断该用哪个。
   */
  function classHint(simple, fqcn) {
    return {
      text: simple,
      displayText: simple + ' — ' + fqcn,
      className: 'hint-method',
      hint: function (cm, data) { pickClass(cm, data, simple, fqcn); }
    };
  }

  /** 已缓存的方法表响应（含 {@code shadowedBy}），没有则 null。 */
  function cachedMethods(target) {
    return state.index.methods[target.kind].get(target.owner) || null;
  }

  /**
   * 拉一个类型的方法表。缓存命中就直接回调，不命中才发请求。
   *
   * <p>敲点号是可预期的一次性动作（不像打字那样连续），所以延迟被藏在这一击里，
   * 之后的筛选全在本地。
   */
  function fetchMethods(target, done) {
    if (!state.index || !target) {
      if (done) { done(); }
      return;
    }
    var bucket = state.index.methods[target.kind];
    if (bucket.has(target.owner)) {
      if (done) { done(); }
      return;
    }
    var query = (target.kind === 'type' ? '?type=' : '?inst=')
            + encodeURIComponent(target.owner);
    api('/api/metadata' + query).then(function (res) {
      if (res.ok && res.data && res.data.methods) {
        bucket.set(target.owner, res.data);
      }
      // 拿不到就不缓存：下次点号再试，而不是把这个名字永久记成「没有方法」
      if (done) { done(); }
    }).catch(function () {
      if (done) { done(); }
    });
  }

  /**
   * 把一条链上每一环的方法表都拉进缓存，包括链尾类型自己的成员表
   * —— 补全列表要列的就是它。
   *
   * <p>任何一环拉不到就停：后面的类型全靠它推，硬猜只会给出错的成员。
   */
  function warmChain(segs, scope, done) {
    if (!segs || !segs.length || segs[0].call) {
      if (done) { done(); }
      return;
    }
    var cur = nameType(segs[0].name, scope);
    if (!cur) {
      if (done) { done(); }
      return;
    }
    var i = 1;
    step();

    function step() {
      if (i >= segs.length) {
        fetchMethods(methodTarget(cur), done);
        return;
      }
      var target = methodTarget(cur);
      var known = cachedMethods(target);
      if (known) {
        advance(cur, known);
        return;
      }
      fetchMethods(target, function () { advance(cur, cachedMethods(target)); });
    }

    function advance(base, known) {
      if (!known) {
        if (done) { done(); }
        return;
      }
      var method = findMethod(known.methods, segs[i].name);
      if (!method) {
        if (done) { done(); }
        return;
      }
      cur = {
        type: substitute(method.returns, known.typeVars, splitGeneric(base.type).args),
        isClass: false
      };
      i++;
      step();
    }
  }

  /** JDK 包要额外拉一次：它们不在 classpath 上，服务端从 jrt 读。 */
  function fetchJdkPackage(pkg, done) {
    if (!state.index || !pkg || state.index.byPackage[pkg]) {
      if (done) { done(); }
      return;
    }
    api('/api/metadata?jdk=' + encodeURIComponent(pkg)).then(function (res) {
      if (res.ok && res.data && res.data.classes) {
        state.index.byPackage[pkg] = res.data.classes;
      }
      if (done) { done(); }
    }).catch(function () {
      if (done) { done(); }
    });
  }

  /** 立刻按当前光标位置弹一次补全；列表为空就不弹。 */
  function showHintNow(cm) {
    if (!cm || cm.state.completionActive) { return; }
    var res = hintSource(cm);
    if (!res || !res.list || !res.list.length) { return; }
    cm.showHint({ hint: hintSource, completeSingle: false });
  }

  /**
   * 敲点号：数据还没到就先拉，拉回来再弹。
   *
   * <p>补全本身是同步的，所以「敲一下问一次服务端」的写法每次都要等一个往返。
   * 这里把等待压在「点号之前那条链首次被用到」的一刻，之后同一类型全是本地查表。
   */
  function handleDot(cm) {
    // 点号这一刻必须用最新的 import 表：防抖是 400ms，连续打字时它一次都不会触发，
    // 于是「刚敲完 import 紧接着敲点号」会读到旧表，把已经 import 的名字当成没 import
    refreshImports();

    var cur = cm.getCursor();
    var line = cm.getLine(cur.line);

    // import 行里的点号是在往下敲包名。JDK 类在 jrt 里，classpath 上找不到，要现拉
    var importLine = /^\s*import\s+(?:static\s+)?([\w$.]+)\.$/.exec(line);
    if (importLine) {
      fetchJdkPackage(importLine[1], function () { showHintNow(cm); });
      return;
    }

    if (!inCodeContext(cm)) { return; }

    // 闭包里的 `a.` 类型来自外层链的元素类型，所以外层链要先热起来
    var closure = closureAt(cm);
    warmChain(closure ? closure.segs : null, null, function () {
      var chain = parseChainAt(line.substring(0, cur.ch), cur.ch);
      warmChain(chain ? chain.segs : null, closureParams(cm), function () {
        showHintNow(cm);
      });
    });
  }

  /** 光标是否在「代码」上下文里（不在注释 / 字符串内）。
   *
   * 补全只在代码里弹：写注释时敲中文说明、写 SQL 字符串时敲表名，
   * 弹一个 Java 方法列表既没用又挡住正在看的字。宁可少弹，不要乱弹。
   */
  function inCodeContext(cm) {
    var cur = cm.getCursor();
    if (cur.ch === 0) { return true; }
    var tok = cm.getTokenAt(cur, true);
    var type = (tok && tok.type) || '';
    return !/(comment|string)/.test(type);
  }

  /** 光标左边正在输入的标识符（没有则空串）。 */
  function wordBeforeCursor(cm) {
    var cur = cm.getCursor();
    var before = cm.getLine(cur.line).substring(0, cur.ch);
    var m = /([A-Za-z_$][\w$]*)$/.exec(before);
    return m ? m[1] : '';
  }

  // ---------------------------------------------------------------------------
  // 编辑器
  // ---------------------------------------------------------------------------

  var STARTER = [
    '// 试跑 = 真的执行，但结束时回滚，不落库。',
    '// Ctrl+Enter 试跑，Ctrl+Shift+Enter 提交，Ctrl+Space 补全。',
    '',
    'return 1 + 1',
    ''
  ].join('\n');

  var TEMPLATES = [
    { label: '基础：返回一个值', text: 'return 1 + 1\n' },
    {
      label: '列出可注入对象',
      text: 'def names = binding.variables.keySet().sort()\nprintln "共 ${names.size()} 个"\nreturn names\n'
    },
    {
      label: '调用 Bean 方法',
      text: 'def value = beanName.methodName()\nprintln "得到 " + value\nreturn value\n'
    },
    {
      label: '遍历并统计',
      text: 'def total = 0\ndef rows = beanName.listSomething()\nrows.each { it ->\n  total++\n}\nprintln "共 " + total + " 条"\nreturn total\n'
    },
    {
      label: 'update 并看影响行数',
      text: '// 影响行数会显示在输出区；超过上限会被守卫拦下并回滚。\ndef n = jdbcTemplate.update("update demo_order set status = \'NEW\' where status = \'PAID\'")\nreturn "影响 " + n + " 行"\n'
    }
  ];

  function initEditor() {
    if (state.cm) { state.cm.refresh(); return; }
    var cm = CodeMirror.fromTextArea(els.editor, {
      mode: 'groovy',
      theme: 'harken',
      lineNumbers: true,
      indentUnit: 2,
      tabSize: 2,
      smartIndent: true,
      matchBrackets: true,
      autoCloseBrackets: true,
      styleActiveLine: true,
      lineWrapping: false,
      // hintOptions 只是 cm.showHint() 的**默认选项来源**，它本身不产生自动弹出
      // （触发时机在下面 inputRead 里，见那段注释）。completeSingle=false 表示
      // 唯一候选也不自动插入 —— 代码是要给人看懂的，不能替他改。
      hintOptions: { hint: hintSource, completeSingle: false, closeOnUnfocus: true },
      extraKeys: {
        'Ctrl-Enter': function () { runScript({ commit: false }); },
        'Cmd-Enter': function () { runScript({ commit: false }); },
        'Shift-Ctrl-Enter': function () { askCommit(); },
        'Shift-Cmd-Enter': function () { askCommit(); },
        'Ctrl-Space': function (editor) { editor.showHint({ hint: hintSource, completeSingle: false }); },
        'Ctrl-K': function () { clearOut(); },
        'Cmd-K': function () { clearOut(); },
        'Ctrl-S': function (editor) {
          localStorage.setItem(DRAFT_KEY, editor.getValue());
          toast('草稿已存到本机浏览器。');
        },
        Esc: function () { closeConfirm(); }
      }
    });

    var draft = localStorage.getItem(DRAFT_KEY);
    cm.setValue(draft && draft.trim() ? draft : STARTER);

    cm.on('change', function () {
      scheduleCompile();
      scheduleDangerScan();
      scheduleImportRefresh();
      localStorage.setItem(DRAFT_KEY, cm.getValue());
    });
    // show-hint 这个 addon 只负责「弹窗 + 随输入筛列表」，它**不判断何时弹**：
    // 里面没有任何 inputRead 绑定（能自动弹是靠各 addon 自己接事件）。
    // 所以触发时机必须在这里定义 —— 否则 hintOptions 配得再全，补全也永远不会自己出现。
    var autoHintTimer = 0;
    cm.on('inputRead', function (editor, change) {
      var typed = change.text ? change.text[0] : '';

      // 成员补全是强意图（敲了点号就是想看有什么方法），当场弹，不等防抖
      if (typed === '.') {
        clearTimeout(autoHintTimer);
        handleDot(editor);
        return;
      }

      // 已经在补全框里：show-hint 会跟着 cursorActivity 自己筛，别再开一个
      if (editor.state.completionActive) { return; }
      // 只认单个标识符字符：数字、空格、括号、粘贴进来的整串都不触发
      if (!/^[A-Za-z_$]$/.test(typed)) { return; }
      if (!inCodeContext(editor)) { return; }

      clearTimeout(autoHintTimer);
      autoHintTimer = setTimeout(function () {
        if (editor.state.completionActive) { return; }
        var word = wordBeforeCursor(editor);
        if (!word) { return; }
        var res = hintSource(editor);
        if (!res || !res.list || !res.list.length) { return; }
        // 已经打完整了就不再弹：`return` 敲完还弹一个 `return` 是纯噪音
        for (var i = 0; i < res.list.length; i++) {
          if (res.list[i].text.toLowerCase() === word.toLowerCase()) { return; }
        }
        editor.showHint({ hint: hintSource, completeSingle: false });
      }, 180);
    });

    state.cm = cm;
    // 首次渲染时容器可能还没完成布局，量出来的尺寸是 0，强制量一次
    setTimeout(function () { cm.refresh(); }, 0);
    setTimeout(function () { cm.focus(); }, 30);
  }

  // ---- 危险 API 行标记 -------------------------------------------------------

  var DANGER_RULES = [
    { re: /\bSystem\s*\.\s*exit\b/, why: 'System.exit 会停掉整个应用' },
    { re: /\bRuntime\s*\.\s*getRuntime\b|\bProcessBuilder\b/, why: '启动外部进程' },
    { re: /\.exec\s*\(/, why: '启动外部进程' },
    { re: /\bnew\s+(?:File|FileWriter|FileReader|FileOutputStream|FileInputStream|RandomAccessFile)\b/, why: '直接读写本机文件' },
    { re: /\bFiles\s*\./, why: '直接读写本机文件' },
    { re: /\bThread\s*\.\s*sleep\b/, why: '阻塞执行线程，大概率撞超时' },
    { re: /\bClass\s*\.\s*forName\b/, why: '动态类加载' },
    { re: /\bshutdown\s*\(|\bstop\s*\(\s*\)/, why: '可能停掉容器' }
  ];

  function scheduleDangerScan() {
    clearTimeout(state.dangerTimer);
    state.dangerTimer = setTimeout(scanDanger, 220);
  }

  function scanDanger() {
    if (!state.cm) { return; }
    var cm = state.cm;
    clearMarks('danger');
    var reasons = [];
    var hits = 0;
    for (var i = 0; i < cm.lineCount(); i++) {
      var line = cm.getLine(i);
      if (!line || line.indexOf('//') === 0) { continue; }
      for (var r = 0; r < DANGER_RULES.length; r++) {
        if (DANGER_RULES[r].re.test(line)) {
          hits++;
          if (reasons.indexOf(DANGER_RULES[r].why) < 0) { reasons.push(DANGER_RULES[r].why); }
          state.marks.danger.push(cm.addLineClass(i, 'background', 'danger-line'));
          break;
        }
      }
    }
    // 这是提示，不是拦截：真正的边界是行数闸、黑名单和事务回滚
    state.lint.danger = hits > 0
      ? '⚠ ' + hits + ' 行含高危 API（' + reasons.join('、') + '）'
      : '';
    renderLint();
  }

  // ---- 编译红线 --------------------------------------------------------------

  function scheduleCompile() {
    clearTimeout(state.compileTimer);
    state.compileTimer = setTimeout(compileNow, 600);
  }

  async function compileNow() {
    if (!state.cm) { return; }
    var script = state.cm.getValue();
    clearMarks('err');
    if (!script.trim()) {
      state.lint.compileText = '';
      state.lint.compileTone = '';
      renderLint();
      return;
    }
    var res;
    try {
      res = await api('/api/compile', { method: 'POST', body: { script: script } });
    } catch (e) {
      return;
    }
    if (sessionLost(res)) { return; }
    // 请求飞在路上时用户又敲了字：这个结果已经过期，丢掉
    if (state.cm.getValue() !== script) { return; }

    if (res.ok) {
      state.lint.compileText = '语法 OK';
      state.lint.compileTone = 'ok';
      renderLint();
      return;
    }
    if (res.status === 400 && res.data.errorType === 'COMPILATION') {
      var line = Number(res.data.line);
      if (line > 0) {
        state.marks.err.push(state.cm.addLineClass(line - 1, 'background', 'err-line'));
        // 补全框开着时不要滚动编辑器。原因：show-hint 在编辑器 scroll 时会重算
        // 提示框位置，一旦算出界就 close()，表现成「打一半提示框自己消失」。
        // 何况用户正在这一行敲字，本来就看得见，把视口拽走纯属帮倒忙。
        // 高亮照常加，只是不滚动。
        if (!state.cm.state.completionActive) {
          state.cm.scrollIntoView({ line: line - 1, ch: 0 }, 80);
        }
      }
      state.lint.compileText = '第 ' + (line > 0 ? line : '?') + ' 行：' + shorten(res.data.error, 90);
      state.lint.compileTone = 'error';
      renderLint();
      return;
    }
    state.lint.compileText = '编译检查失败：' + shorten(errText(res), 90);
    state.lint.compileTone = 'warn';
    renderLint();
  }

  function clearMarks(kind) {
    var cm = state.cm;
    if (!cm) { return; }
    var cls = kind === 'err' ? 'err-line' : 'danger-line';
    state.marks[kind].forEach(function (handle) {
      cm.removeLineClass(handle, 'background', cls);
    });
    state.marks[kind] = [];
  }

  /**
   * 工具栏那一条提示是两路信息合成的：编译结果与危险 API 扫描。
   * 分两个字段存、最后合成显示，是因为它们由两个独立的定时器产出 ——
   * 谁后跑完谁覆盖的话，用户会看到提示在「语法错误」和「⚠ 2 行高危」之间来回跳。
   * 优先级：编译错误 > 危险 API > 语法 OK。
   */
  function renderLint() {
    var text = '';
    var tone = '';
    if (state.lint.compileTone === 'error') {
      text = state.lint.compileText;
      tone = 'error';
    } else if (state.lint.danger) {
      text = state.lint.danger;
      tone = 'warn';
    } else if (state.lint.compileTone === 'ok') {
      text = state.lint.compileText;
      tone = 'ok';
    }
    els.lint.textContent = text;
    els.lint.className = 'lint' + (tone ? ' is-' + tone : '');
    els.lint.title = text;
  }

  function shorten(text, max) {
    if (!text) { return ''; }
    var oneLine = String(text).split('\n')[0];
    return oneLine.length <= max ? oneLine : oneLine.substring(0, max) + '…';
  }

  // ---------------------------------------------------------------------------
  // 执行（两阶段）
  // ---------------------------------------------------------------------------

  function setBusy(busy, label) {
    state.busy = busy;
    els.btnDryrun.disabled = busy;
    els.btnCommit.disabled = busy || !canCommitNow();
    els.runState.textContent = busy ? (label || '执行中…') : '';
    els.runState.className = 'run-state' + (busy ? ' is-busy' : '');
  }

  function canCommitNow() {
    return !!(state.health && state.health.canCommit);
  }

  function updateCommitButton() {
    if (state.busy) { return; }
    var can = canCommitNow();
    var ready;
    if (state.restricted) {
      // 受限模式：选中了脚本、脚本允许提交、且已对该脚本试跑成功，才允许提交。
      ready = !!(state.selectedScriptId && state.selectedDef
        && state.selectedDef.commitAllowed !== false
        && state.lastRun && state.lastRun.scriptId === state.selectedScriptId && state.lastRun.ok);
    } else {
      ready = state.lastRun && state.lastRun.ok && !state.lastRun.committed;
    }
    els.btnCommit.disabled = !can || !ready;
    els.btnCommit.title = !can
      ? '当前角色 ' + (state.health ? state.health.role : '') + ' 不允许提交'
      : (ready ? '提交这次试跑的改动（会要求输入环境名确认）' : '先试跑当前脚本，确认影响行数后才能提交');
  }

  async function runScript(options) {
    if (state.busy) { return; }
    var commit = !!(options && options.commit);
    var script;
    var params;
    if (state.restricted) {
      // 受限模式：执行入口是「一个 scriptId + 参数」，不是任意脚本文本。
      if (!state.selectedScriptId) {
        toast('请先从左边选一个脚本。', true);
        return;
      }
      params = collectParams();
      if (params === null) { return; }   // 必填校验未过，提示已给出
      script = state.selectedDef ? state.selectedDef.name : ('#' + state.selectedScriptId);
    } else {
      if (!state.cm) { return; }
      script = state.cm.getValue();
      if (!script.trim()) {
        toast('脚本是空的。', true);
        return;
      }
      params = {};
    }
    closeConfirm();
    setBusy(true, commit ? '提交中…' : '试跑中…');
    els.outTitle.textContent = commit ? '提交结果' : '试跑结果';
    els.outMeta.textContent = '';

    try {
      var body = state.restricted
        ? { scriptId: state.selectedScriptId, params: params, commit: commit }
        : { script: script, commit: commit };
      var res = await api('/api/execute', { method: 'POST', body: body });
      if (sessionLost(res)) { return; }

      if (res.status === 403 && res.data.errorType === 'HARKEN_FORBIDDEN_COMMIT') {
        renderRejection(res.data);
        toast('没有提交权限。', true);
        return;
      }
      if (!res.ok && res.data.ok === undefined) {
        renderRejection(res.data);
        toast(errText(res), true);
        return;
      }

      renderResult(res.data, commit, script);
      recordLocal(res.data, script, commit);
      await loadHistory();
    } catch (e) {
      renderRejection({ ok: false, errorType: 'NETWORK', error: '连不上服务端：' + e.message });
    } finally {
      setBusy(false);
      updateCommitButton();
    }
  }

  function renderRejection(data) {
    els.out.textContent = '';
    els.out.appendChild(outLine('请求被拒绝', 'bad'));
    els.out.appendChild(outLine('类型：' + (data.errorType || '-'), 'dim'));
    if (data.error) { els.out.appendChild(outLine(data.error, '')); }
  }

  function outLine(text, cls) {
    var div = node('div', cls || null, text);
    return div;
  }

  function renderResult(result, commit, script) {
    var ok = result.ok !== false;
    els.out.textContent = '';

    var head = ok ? '执行成功' : '执行失败';
    els.out.appendChild(outLine(head, ok ? 'good' : 'bad'));

    var state2 = result.rolledBack ? '已回滚（未落库）' : '未回滚（改动已生效）';
    var rows = result.affectedRows;
    var rowsText = (rows === undefined || rows === null || rows < 0)
      ? '影响行数：未测量' : ('影响行数：' + rows);
    els.out.appendChild(outLine(
      state2 + ' · ' + rowsText + ' · ' + result.durationMs + 'ms · trace ' + (result.traceId || '-'),
      'dim'
    ));

    if (result.errorType) {
      els.out.appendChild(outLine(''));
      els.out.appendChild(outLine('错误类型：' + result.errorType, 'bad'));
    }
    if (result.error) {
      els.out.appendChild(outLine(''));
      els.out.appendChild(outLine(result.error, 'bad'));
    }
    if (result.output) {
      els.out.appendChild(outLine(''));
      els.out.appendChild(outLine('— 输出 —', 'sec'));
      els.out.appendChild(outLine(trimTrailing(result.output), ''));
    }
    if (result.result !== null && result.result !== undefined) {
      els.out.appendChild(outLine(''));
      els.out.appendChild(outLine('— 返回值 —', 'sec'));
      els.out.appendChild(outLine(result.result === '' ? '(空字符串)' : String(result.result), ''));
    }
    if (result.truncated) {
      els.out.appendChild(outLine(''));
      els.out.appendChild(outLine('（结果已截断）' + (result.hint || ''), 'dim'));
    }

    els.outMeta.textContent = ok ? 'ok' : 'failed';

    state.lastRun = {
      hash: hash(script),
      scriptId: state.restricted ? state.selectedScriptId : null,
      ok: ok && !commit,
      commit: commit,
      committed: commit && ok && !result.rolledBack,
      affectedRows: rows
    };
    if (commit && ok && !result.rolledBack) {
      toast('已提交：影响 ' + (rows < 0 ? '未知' : rows) + ' 行。');
    } else if (!ok) {
      state.lastRun.ok = false;
    }
  }

  function trimTrailing(text) {
    return String(text).replace(/\s+$/, '');
  }

  // ---------------------------------------------------------------------------
  // 提交前的二次确认
  // ---------------------------------------------------------------------------

  function askCommit() {
    if (state.busy) { return; }
    if (!canCommitNow()) {
      toast('当前角色不允许提交。', true);
      return;
    }
    if (state.restricted) {
      // 受限模式：提交前必须先对「当前选中的脚本」试跑成功，且脚本本身允许提交。
      if (!state.selectedScriptId) {
        toast('先选一个脚本再提交。', true);
        return;
      }
      if (!state.lastRun || state.lastRun.scriptId !== state.selectedScriptId || !state.lastRun.ok) {
        toast('请先试跑当前脚本，确认影响行数后再提交。', true);
        return;
      }
      if (state.selectedDef && state.selectedDef.commitAllowed === false) {
        toast('该脚本不允许提交（commitAllowed=false）。', true);
        return;
      }
    } else {
      var script = state.cm.getValue();
      if (!state.lastRun || state.lastRun.hash !== hash(script) || !state.lastRun.ok) {
        toast('请先试跑当前脚本，确认影响行数后再提交。', true);
        return;
      }
    }
    var expected = (state.health.environment || 'COMMIT').trim();
    var rows = state.lastRun.affectedRows;
    els.confirmHead.textContent = '确认把改动写入数据库';
    els.confirmBody.textContent = '试跑影响 ' + (rows < 0 ? '未知数量' : rows) + ' 行。'
      + '请输入 ' + expected + ' 解锁「确认提交」。';
    els.confirmInput.value = '';
    els.confirmInput.placeholder = expected;
    els.btnConfirmCommit.disabled = true;
    els.confirmBar.hidden = false;
    els.confirmInput.focus();
  }

  function closeConfirm() {
    els.confirmBar.hidden = true;
    els.confirmInput.value = '';
    els.btnConfirmCommit.disabled = true;
  }

  els.confirmInput.addEventListener('input', function () {
    var expected = (state.health && state.health.environment ? state.health.environment : 'COMMIT').trim();
    var typed = els.confirmInput.value.trim();
    els.btnConfirmCommit.disabled = typed.toLowerCase() !== expected.toLowerCase();
  });

  els.confirmInput.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && !els.btnConfirmCommit.disabled) {
      event.preventDefault();
      runScript({ commit: true });
      return;
    }
    // 焦点在输入框里时，CodeMirror 的 Esc 快捷键收不到事件，这里单独接一次
    if (event.key === 'Escape') {
      event.preventDefault();
      closeConfirm();
    }
  });

  els.btnConfirmCommit.addEventListener('click', function () { runScript({ commit: true }); });
  els.btnConfirmCancel.addEventListener('click', closeConfirm);

  // ---------------------------------------------------------------------------
  // 输出区
  // ---------------------------------------------------------------------------

  function clearOut() {
    els.out.textContent = '';
    els.outTitle.textContent = '输出';
    els.outMeta.textContent = '';
  }

  // ---------------------------------------------------------------------------
  // 历史：本机 localStorage + 服务端审计，按 traceId 合并
  // ---------------------------------------------------------------------------

  function loadLocal() {
    try {
      var raw = localStorage.getItem(HISTORY_KEY);
      var list = raw ? JSON.parse(raw) : [];
      return Array.isArray(list) ? list : [];
    } catch (e) {
      return [];
    }
  }

  function recordLocal(result, script, commit) {
    if (!result || !result.traceId) { return; }
    var list = loadLocal().filter(function (e) { return e.traceId !== result.traceId; });
    list.unshift({
      traceId: result.traceId,
      at: new Date().toISOString(),
      ok: result.ok !== false,
      rolledBack: !!result.rolledBack,
      commitRequested: !!commit,
      affectedRows: result.affectedRows,
      durationMs: result.durationMs,
      errorType: result.errorType || null,
      error: result.error || null,
      preview: firstLine(script),
      script: script
    });
    try {
      localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, HISTORY_MAX)));
    } catch (e) {
      // 配额满了就放弃写本地：历史丢了不影响执行，不该因此报错打扰用户
    }
  }

  function firstLine(text) {
    var lines = String(text).split('\n');
    for (var i = 0; i < lines.length; i++) {
      var t = lines[i].trim();
      if (t) { return t.length > 120 ? t.substring(0, 120) + '…' : t; }
    }
    return '(空脚本)';
  }

  async function loadHistory() {
    if (!state.health) { return; }
    var local = loadLocal();
    var scripts = {};
    local.forEach(function (e) { scripts[e.traceId] = e.script; });

    var server = [];
    if (state.health.audit && state.health.audit.queryable) {
      try {
        var res = await api('/api/audits?limit=50');
        if (sessionLost(res)) { return; }
        if (res.ok) { server = res.data.records || []; }
      } catch (e) { /* 审计读不出来不该让面板报错 */ }
    }

    var seen = {};
    var merged = server.map(function (rec) {
      seen[rec.traceId] = true;
      return { rec: rec, script: scripts[rec.traceId] || null };
    });
    local.forEach(function (e) {
      if (seen[e.traceId]) { return; }
      merged.push({
        rec: {
          traceId: e.traceId, at: e.at, channel: 'human',
          operator: state.me ? state.me.username : null,
          ok: e.ok, rolledBack: e.rolledBack, commitRequested: e.commitRequested,
          affectedRows: e.affectedRows, durationMs: e.durationMs,
          errorType: e.errorType, error: e.error, scriptPreview: e.preview
        },
        script: e.script
      });
    });

    merged.sort(function (a, b) {
      return String(b.rec.at || '').localeCompare(String(a.rec.at || ''));
    });
    renderHistory(merged.slice(0, 60));
  }

  function renderHistory(items) {
    els.history.textContent = '';
    els.historyEmpty.hidden = items.length > 0;
    items.forEach(function (item) {
      var rec = item.rec;
      var li = node('li', 'history-item ' + (rec.ok ? 'is-ok' : 'is-fail')
        + (item.script ? ' is-clickable' : ''));

      li.appendChild(node('div', 'h-time', clockOf(rec.at)));

      var main = node('div', 'h-main');
      var line1 = node('div', 'h-line1');

      var tag;
      if (!rec.ok) {
        tag = node('span', 'h-tag bad', '失败');
      } else if (rec.commitRequested && !rec.rolledBack) {
        tag = node('span', 'h-tag ok', '已提交');
      } else if (rec.rolledBack) {
        tag = node('span', 'h-tag', '已回滚');
      } else {
        tag = node('span', 'h-tag warn', '未回滚');
      }
      line1.appendChild(tag);

      if (rec.channel === 'ai') { line1.appendChild(node('span', 'h-tag', 'AI')); }
      line1.appendChild(node('span', 'h-preview', rec.scriptPreview || rec.errorType || '(无预览)'));
      main.appendChild(line1);

      var bits = [];
      if (rec.affectedRows >= 0) { bits.push(rec.affectedRows + ' 行'); }
      if (rec.durationMs !== undefined && rec.durationMs !== null) { bits.push(rec.durationMs + 'ms'); }
      if (rec.operator) { bits.push(rec.operator); }
      bits.push('trace ' + (rec.traceId || '-'));
      main.appendChild(node('div', 'h-line2', bits.join(' · ')));

      li.appendChild(main);

      if (item.script) {
        li.title = '点击把这段脚本填回编辑器';
        li.addEventListener('click', function () {
          state.cm.setValue(item.script);
          state.cm.focus();
          toast('已回填 ' + item.script.split('\n').length + ' 行脚本（不会执行）。');
        });
      } else {
        li.title = '记录来自服务端审计，本机没有保存脚本原文';
      }

      els.history.appendChild(li);
    });
  }

  // ---------------------------------------------------------------------------
  // 模板
  // ---------------------------------------------------------------------------

  function initTemplates() {
    TEMPLATES.forEach(function (t, i) {
      var opt = document.createElement('option');
      opt.value = String(i);
      opt.textContent = t.label;
      els.tplSelect.appendChild(opt);
    });
    els.tplSelect.addEventListener('change', function () {
      var idx = els.tplSelect.value;
      if (idx === '') { return; }
      if (idx.indexOf('cat:') === 0 && state.cm) {
        // FULL 模式：把预置目录里的脚本当模板插入编辑器（源码已随 catalog 下发）
        var id = idx.substring(4);
        var d = state.catalogScripts[id];
        if (d && d.script) {
          var cur = state.cm.getCursor();
          var lineText = state.cm.getLine(cur.line);
          if (lineText.trim() === '') {
            state.cm.replaceRange(d.script, { line: cur.line, ch: 0 },
              { line: cur.line, ch: lineText.length });
          } else {
            state.cm.replaceRange('\n' + d.script, cur);
          }
          state.cm.focus();
        }
        els.tplSelect.value = '';
        return;
      }
      var tpl = TEMPLATES[Number(idx)];
      if (tpl && state.cm) {
        var cur2 = state.cm.getCursor();
        var lineText2 = state.cm.getLine(cur2.line);
        // 空行直接替换整行；行内有内容就在其后另起一段，避免把已有代码切断
        if (lineText2.trim() === '') {
          state.cm.replaceRange(tpl.text, { line: cur2.line, ch: 0 },
            { line: cur2.line, ch: lineText2.length });
        } else {
          state.cm.replaceRange('\n' + tpl.text, cur2);
        }
        state.cm.focus();
      }
      els.tplSelect.value = '';
    });
  }

  // ---------------------------------------------------------------------------
  // 受限模式：预置脚本目录 + 参数表单
  // ---------------------------------------------------------------------------

  async function loadCatalog() {
    try {
      var res = await api('/api/catalog');
      if (sessionLost(res)) { return; }
      if (!res.ok) {
        toast('读取脚本目录失败：' + errText(res), true);
        return;
      }
      renderCatalog(res.data);
    } catch (e) {
      toast('读取脚本目录失败：' + e.message, true);
    }
  }

  function renderCatalog(data) {
    state.catalogDefs = {};
    state.catalogScripts = {};
    els.catalogRisk.textContent = data.maxRisk ? ('最高危险等级 ' + data.maxRisk) : '';
    els.catalogList.textContent = '';
    var defs = data.scripts || [];
    if (state.restricted) {
      defs.forEach(function (d) {
        state.catalogDefs[d.id] = d;
        els.catalogList.appendChild(catalogItem(d));
      });
      els.catalogEmpty.hidden = defs.length > 0;
    } else {
      // FULL 模式：把目录脚本作为可插入编辑器的模板
      defs.forEach(function (d) {
        state.catalogScripts[d.id] = d;
      });
      populateCatalogTemplates(defs);
    }
  }

  function catalogItem(d) {
    var li = node('li', 'catalog-item');
    li.dataset.scriptId = d.id;
    li.appendChild(node('span', 'catalog-item-name', d.name));
    if (d.description) {
      li.appendChild(node('span', 'catalog-item-desc', d.description));
    }
    var meta = node('span', 'catalog-item-meta');
    meta.appendChild(riskChip(d.risk));
    meta.appendChild(d.commitAllowed
      ? node('span', 'chip is-ok', '可提交')
      : node('span', 'chip is-muted', '仅试跑'));
    li.appendChild(meta);
    li.addEventListener('click', function () { selectCatalogItem(d); });
    return li;
  }

  function riskChip(risk) {
    var tone = risk === 'DANGEROUS' ? 'is-danger' : (risk === 'WRITE' ? 'is-warn' : 'is-ok');
    return node('span', 'chip ' + tone, risk || 'READ');
  }

  function selectCatalogItem(d) {
    state.selectedScriptId = d.id;
    state.selectedDef = d;
    state.lastRun = null;   // 选中新脚本后，必须重新试跑才能提交
    els.catalogList.querySelectorAll('.catalog-item').forEach(function (el) {
      el.classList.toggle('is-selected', el.dataset.scriptId === d.id);
    });
    els.paramPanel.hidden = false;
    els.paramScriptName.textContent = d.name;
    var tone = d.risk === 'DANGEROUS' ? 'is-danger' : (d.risk === 'WRITE' ? 'is-warn' : 'is-ok');
    els.paramScriptRisk.className = 'chip ' + tone;
    els.paramScriptRisk.textContent = d.risk || 'READ';
    els.paramScriptDesc.textContent = d.description || '';
    buildParamForm(d);
    updateCommitButton();
    clearOut();
  }

  function buildParamForm(d) {
    els.paramForm.textContent = '';
    var params = d.params || [];
    if (params.length === 0) {
      els.paramHelp.hidden = false;
      els.paramHelp.textContent = '这个脚本不需要参数，直接试跑即可。';
      return;
    }
    els.paramHelp.hidden = true;
    params.forEach(function (p) {
      var field = node('div', 'field');
      var req = p.required ? ' *' : '';
      field.appendChild(node('span', null, (p.label || p.name) + req));
      var input;
      if (p.type === 'boolean') {
        input = document.createElement('input');
        input.type = 'checkbox';
      } else if (p.type === 'number') {
        input = document.createElement('input');
        input.type = 'number';
      } else if (p.type === 'date') {
        input = document.createElement('input');
        input.type = 'date';
      } else {
        input = document.createElement('input');
        input.type = 'text';
      }
      input.className = 'param-input';
      input.dataset.param = p.name;
      input.dataset.type = p.type || 'string';
      if (p.defaultValue != null) { input.value = p.defaultValue; }
      if (p.description) { input.placeholder = p.description; }
      field.appendChild(input);
      els.paramForm.appendChild(field);
    });
  }

  function collectParams() {
    var def = state.selectedDef;
    if (!def) { return {}; }
    var out = {};
    var inputs = els.paramForm.querySelectorAll('[data-param]');
    for (var i = 0; i < inputs.length; i++) {
      var input = inputs[i];
      var name = input.dataset.param;
      var type = input.dataset.type || 'string';
      var raw = (type === 'boolean') ? input.checked : input.value;
      var pdef = null;
      for (var j = 0; j < def.params.length; j++) {
        if (def.params[j].name === name) { pdef = def.params[j]; break; }
      }
      var required = pdef && pdef.required;
      if (required && type !== 'boolean' && (raw == null || String(raw).trim() === '')) {
        toast('请填写必填参数：' + (pdef.label || name), true);
        return null;
      }
      out[name] = raw;
    }
    return out;
  }

  function populateCatalogTemplates(defs) {
    if (!els.tplSelect) { return; }
    defs.forEach(function (d) {
      if (!d.script) { return; }
      var opt = document.createElement('option');
      opt.value = 'cat:' + d.id;
      opt.textContent = '目录：' + d.name;
      els.tplSelect.appendChild(opt);
    });
  }

  // ---------------------------------------------------------------------------
  // 启动
  // ---------------------------------------------------------------------------

  async function loadConsole() {
    var res = await api('/api/health');
    if (sessionLost(res)) { return; }
    if (!res.ok) {
      toast('读取状态失败：' + errText(res), true);
      return;
    }
    state.health = res.data;
    state.restricted = !!res.data.restricted;
    renderChips();
    renderBanner();

    // 受限模式：不渲染编辑器，改渲染预置脚本目录（RESTRICTED 不下发源码）。
    // 目录接口两种模式都能调，FULL 模式用它把脚本当模板插进编辑器。
    await loadCatalog();

    if (state.restricted) {
      els.editorWrap.hidden = true;
      els.tplWrap.hidden = true;
      els.catalogView.hidden = false;
      state.selectedScriptId = null;
      state.selectedDef = null;
      updateCommitButton();
    } else {
      els.editorWrap.hidden = false;
      els.tplWrap.hidden = false;
      els.catalogView.hidden = true;
      initEditor();
      try {
        var meta = await api('/api/metadata');
        if (sessionLost(meta)) { return; }
        if (meta.ok) { buildIndex(meta.data); }
      } catch (e) { /* 补全不可用不影响执行 */ }
    }

    await loadHistory();
  }

  els.btnDryrun.addEventListener('click', function () { runScript({ commit: false }); });
  els.btnCommit.addEventListener('click', askCommit);
  els.btnClear.addEventListener('click', clearOut);
  els.btnHistory.addEventListener('click', loadHistory);
  window.addEventListener('resize', function () {
    if (state.cm) { state.cm.refresh(); }
  });

  initTemplates();
  initTheme();

  api('/auth/me').then(function (res) {
    if (res.status === 200) {
      enterConsole(res.data);
    } else {
      showLogin('');
    }
  }).catch(function (e) {
    showLogin('连不上服务端：' + e.message);
  });
})();
