package com.harlanking.harken.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 白名单的匹配规则。
 */
class IpAllowListTest {

    @Test
    @DisplayName("精确匹配")
    void exactMatch() {
        IpAllowList list = IpAllowList.parse(List.of("127.0.0.1", "10.1.2.3"));

        assertTrue(list.allows("127.0.0.1"));
        assertTrue(list.allows("10.1.2.3"));
        assertFalse(list.allows("10.1.2.4"));
    }

    @Test
    @DisplayName("IPv4 CIDR 覆盖整段")
    void cidrMatch() {
        IpAllowList list = IpAllowList.parse(List.of("172.16.0.0/12"));

        assertTrue(list.allows("172.16.0.1"));
        assertTrue(list.allows("172.31.255.254"));
        assertFalse(list.allows("172.32.0.1"));
        assertFalse(list.allows("172.15.255.255"));
    }

    @Test
    @DisplayName("双栈容器给出 ::ffff:127.0.0.1 时，IPv4 规则也要命中")
    void ipv4MappedIpv6() {
        IpAllowList list = IpAllowList.parse(List.of("127.0.0.1"));

        assertTrue(list.allows("::ffff:127.0.0.1"));
        // 只配了 IPv4 回环，纯 IPv6 回环不该被顺带放行
        assertFalse(list.allows("::1"));
    }

    @Test
    @DisplayName("空名单 = 全拒绝（绑定出岔子时宁可连不上）")
    void emptyDeniesAll() {
        IpAllowList list = IpAllowList.parse(List.of());

        assertFalse(list.allows("127.0.0.1"));
        assertFalse(list.allows(null));
        assertFalse(list.allows(""));
    }

    @Test
    @DisplayName("* 显式放开全部")
    void wildcardAllowsAll() {
        IpAllowList list = IpAllowList.parse(List.of("*"));

        assertTrue(list.isAllowAll());
        assertTrue(list.allows("8.8.8.8"));
    }

    @Test
    @DisplayName("CIDR 写错要在启动时炸掉，不能静默变成「不匹配」")
    void invalidCidrFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> IpAllowList.parse(List.of("10.0.0.0/33")));
        assertThrows(IllegalArgumentException.class, () -> IpAllowList.parse(List.of("10.0.0/8")));
        assertThrows(IllegalArgumentException.class, () -> IpAllowList.parse(List.of("abc/8")));
    }

    @Test
    @DisplayName("非法来源地址一律拒绝")
    void garbageAddressDenied() {
        IpAllowList list = IpAllowList.parse(List.of("10.0.0.0/8"));

        assertFalse(list.allows("not-an-ip"));
        assertFalse(list.allows("10.0.0.256"));
        assertFalse(list.allows("10.0.0"));
    }

    @Test
    @DisplayName("describe 返回原始条目，供启动日志原样打印")
    void describeKeepsRawEntries() {
        IpAllowList list = IpAllowList.parse(List.of(" 127.0.0.1 ", "10.0.0.0/8"));
        assertEquals(List.of("127.0.0.1", "10.0.0.0/8"), list.describe());
    }
}
