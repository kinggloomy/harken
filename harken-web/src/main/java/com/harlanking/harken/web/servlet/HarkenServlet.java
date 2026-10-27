package com.harlanking.harken.web.servlet;

import com.harlanking.harken.web.json.JsonException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 所有 Harken servlet 的基类：只做一件事 —— 把异常变成响应。
 *
 * <h2>为什么不用 Spring MVC</h2>
 * <p>用 Servlet 而不是 {@code @RestController}，是为了让这个模块在没有 Spring 的应用里
 * 也能跑（Tomcat/Jetty/Undertow 内嵌，或者外部容器）。代价是要自己写路由，
 * 收益是「加一个 jar 就能用」在任何 Java Web 应用里都成立。
 *
 * <h2>异常兜底的三层</h2>
 * <ol>
 *   <li>{@link Http.Error} —— 明确的、已知的失败，按原状态码返回</li>
 *   <li>{@link JsonException} —— 请求体不是合法 JSON，一律 400</li>
 *   <li>其他 —— 500，并且<b>不把堆栈返回给客户端</b>，只记日志。
 *       堆栈里常有连接串、表名、文件路径，泄漏出去比多调试十分钟更糟。</li>
 * </ol>
 *
 * @author harlan
 * @since 1.0.0
 */
public abstract class HarkenServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(HarkenServlet.class);

    /** 构造器。 */
    protected HarkenServlet() {
    }

    /**
     * 子类实现真正的路由与逻辑。
     *
     * @param req  请求
     * @param resp 响应
     * @throws Exception 任何异常都会被基类转成响应
     */
    protected abstract void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception;

    @Override
    protected final void service(HttpServletRequest req, HttpServletResponse resp) {
        try {
            handle(req, resp);
        } catch (Http.Error e) {
            writeQuietly(resp, e);
        } catch (JsonException e) {
            writeQuietly(resp, Http.badRequest("请求体不是合法 JSON：" + e.getMessage()));
        } catch (Throwable t) {
            log.error("Harken: 处理 {} {} 时发生未预期错误",
                    req.getMethod(), req.getRequestURI(), t);
            writeQuietly(resp, new Http.Error(500, "HARKEN_INTERNAL_ERROR",
                    "服务端内部错误，详情见服务端日志。"));
        }
    }

    private void writeQuietly(HttpServletResponse resp, Http.Error error) {
        if (resp.isCommitted()) {
            // 已经写出去了就不要再写，否则容器会抛 IllegalStateException，把原始错误盖掉
            log.warn("Harken: 响应已提交，无法写出错误 {}", error.code());
            return;
        }
        try {
            Http.error(resp, error);
        } catch (IOException | RuntimeException e) {
            log.warn("Harken: 写出错误响应失败 {}", error.code(), e);
        }
    }
}
