/* 主题引导。
 *
 * 必须同步执行、且必须排在样式表之前：浏览器解析到 <html> 就会开始画，
 * 若等到 body 末尾的 app.js 才设 data-theme，浅色用户每次刷新都会先看到
 * 一帧深色再跳亮 —— 这种闪一下比没有主题更刺眼。
 *
 * 单独成文件而不是内联：CSP 是 script-src 'self'，内联脚本会被直接拦掉。
 * 代价是多一次同源请求（<300 字节，且带 ETag 可 304），换来无闪烁与不用维护 hash。
 */
try {
  if (localStorage.getItem('harken.theme.v1') === 'light') {
    document.documentElement.setAttribute('data-theme', 'light');
  }
} catch (e) {
  /* 存储不可用（无痕模式等）时保持默认深色 */
}
