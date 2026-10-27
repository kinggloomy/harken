package com.harlanking.harken.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * IP 白名单。
 *
 * <h2>支持三种写法</h2>
 * <ul>
 *   <li>{@code 127.0.0.1} —— 精确匹配</li>
 *   <li>{@code 10.0.0.0/8} —— IPv4 CIDR（内网段）</li>
 *   <li>{@code *} —— 全部放行（显式写出来，便于 grep 到「这里没有 IP 限制」）</li>
 * </ul>
 *
 * <p>IPv6 只支持精确匹配。给 IPv6 做 CIDR 需要正确解析压缩格式与前缀合并，
 * 而实际用到的场景只有 {@code ::1} 和 {@code ::ffff:127.0.0.1} 这两种回环形式 ——
 * 假支持比不支持更危险。
 *
 * <h2>为什么白名单为空时是「全拒绝」</h2>
 * <p>空配置最可能的成因是「绑定配置时出了岔子」，此时放行意味着防线静默消失。
 * 宁可连不上（立刻能发现并修），也不要静默敞开。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class IpAllowList {

    private final boolean allowAll;
    private final Set<String> exact;
    private final List<Ipv4Range> ranges;
    private final List<String> rawEntries;

    private IpAllowList(boolean allowAll, Set<String> exact, List<Ipv4Range> ranges, List<String> rawEntries) {
        this.allowAll = allowAll;
        this.exact = exact;
        this.ranges = ranges;
        this.rawEntries = rawEntries;
    }

    /**
     * 解析配置项。
     *
     * @param entries 配置里的条目列表，可为 {@code null}
     * @return 白名单
     * @throws IllegalArgumentException CIDR 格式错误
     */
    public static IpAllowList parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return new IpAllowList(false, Set.of(), List.of(), List.of());
        }
        boolean allowAll = false;
        Set<String> exact = new LinkedHashSet<>();
        List<Ipv4Range> ranges = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String v = entry.trim();
            raw.add(v);
            if ("*".equals(v)) {
                allowAll = true;
                continue;
            }
            if (v.indexOf('/') > 0) {
                ranges.add(Ipv4Range.parse(v));
            } else {
                exact.add(v.toLowerCase(Locale.ROOT));
            }
        }
        return new IpAllowList(allowAll, Set.copyOf(exact), List.copyOf(ranges), List.copyOf(raw));
    }

    /**
     * 判断来源地址是否允许。
     *
     * @param ip 客户端地址，通常是 {@code request.getRemoteAddr()}；{@code null} 视为拒绝
     * @return 是否放行
     */
    public boolean allows(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (allowAll) {
            return true;
        }
        String addr = ip.trim().toLowerCase(Locale.ROOT);
        if (exact.contains(addr)) {
            return true;
        }
        // IPv4-mapped IPv6（双栈容器常见）：::ffff:127.0.0.1 也要能用 IPv4 规则匹配
        String v4 = addr.startsWith("::ffff:") ? addr.substring("::ffff:".length()) : addr;
        if (exact.contains(v4)) {
            return true;
        }
        int n = ipv4ToInt(v4);
        if (n == Integer.MIN_VALUE) {
            return false;
        }
        for (Ipv4Range r : ranges) {
            if (r.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否显式放开所有地址。
     *
     * @return {@code true} 表示配置了 {@code *}
     */
    public boolean isAllowAll() {
        return allowAll;
    }

    /**
     * @return 原始配置条目，用于启动日志
     */
    public List<String> describe() {
        return rawEntries;
    }

    /**
     * 把点分十进制 IPv4 转成 32 位整数。
     *
     * @param ip 地址
     * @return 整数，非法时返回 {@link Integer#MIN_VALUE} 作为哨兵
     */
    static int ipv4ToInt(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return Integer.MIN_VALUE;
        }
        int value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return Integer.MIN_VALUE;
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return Integer.MIN_VALUE;
            }
            if (octet < 0 || octet > 255) {
                return Integer.MIN_VALUE;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    /** 一个 IPv4 网段。 */
    private static final class Ipv4Range {

        private final int network;
        private final int mask;

        private Ipv4Range(int network, int mask) {
            this.network = network & mask;
            this.mask = mask;
        }

        static Ipv4Range parse(String text) {
            int slash = text.indexOf('/');
            String addr = text.substring(0, slash);
            String prefixText = text.substring(slash + 1);
            int ip = ipv4ToInt(addr.trim());
            if (ip == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("不是合法的 IPv4 地址：" + addr + "（条目 " + text + "）");
            }
            int prefix;
            try {
                prefix = Integer.parseInt(prefixText.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CIDR 前缀不是数字：" + text);
            }
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("CIDR 前缀必须在 0~32 之间：" + text);
            }
            int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
            return new Ipv4Range(ip, mask);
        }

        boolean contains(int ip) {
            return (ip & mask) == network;
        }
    }
}
