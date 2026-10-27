package com.harlanking.harken.web.servlet;

import com.harlanking.harken.web.IpAllowList;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * IP 白名单过滤器。挂在 {@code {basePath}/*} 上，是人通道与 AI 通道<b>共同</b>的第一道门。
 *
 * <h2>为什么这道门在所有认证之前</h2>
 * <p>登录接口本身是不能要求登录的，所以它是整个系统里唯一「无凭据可访问」的入口。
 * 把 IP 白名单放在它前面，相当于在暴力破解开始之前就把不在名单上的人挡掉 ——
 * 攻击面从一个 HTTP 服务缩小到「回环地址」。
 *
 * <h2>默认只允许回环，这不是「不方便」而是设计</h2>
 * <p>Harken 的目标场景是「运维人员 SSH 到机器上，用 curl 或者 SSH 隧道访问」。
 * 在这个场景里，回环就够了。想从办公网直接访问，必须显式改配置 ——
 * 这个「必须显式」的动作，就是让「我知道我正在把数据库写权限暴露到网络上」这句话被说出来。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class IpAllowlistFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(IpAllowlistFilter.class);

    private final IpAllowList allowList;
    private final boolean trustForwardedFor;

    /**
     * @param allowList         白名单
     * @param trustForwardedFor 是否信任 {@code X-Forwarded-For}
     */
    public IpAllowlistFilter(IpAllowList allowList, boolean trustForwardedFor) {
        this.allowList = allowList;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }
        String ip = Http.clientIp(req, trustForwardedFor);
        if (!allowList.allows(ip)) {
            log.warn("Harken: 拒绝来自 {} 的访问（{} {}），不在 IP 白名单内",
                    ip, req.getMethod(), req.getRequestURI());
            Http.error(resp, Http.forbidden("HARKEN_IP_FORBIDDEN",
                    "来源地址 " + ip + " 不在白名单内。"
                            + "白名单配置项：harken.web.allowed-ips"));
            return;
        }
        chain.doFilter(request, response);
    }
}
