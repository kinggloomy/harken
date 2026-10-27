package com.harlanking.harken.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GuardPolicy} 的匹配规则。表名大小写与通配符是最容易出错的两处。
 */
class GuardPolicyTest {

    @Test
    @DisplayName("精确名、头部通配、尾部通配都能命中，且统一按小写比较")
    void matchesExactAndWildcard() {
        GuardPolicy policy = new GuardPolicy(0, List.of("supply_order", "*_payment", "tmp_*"), false);

        assertTrue(policy.denies("supply_order"));
        assertTrue(policy.denies("SUPPLY_ORDER"));
        assertTrue(policy.denies("order_payment"));
        assertTrue(policy.denies("tmp_scratch"));
        assertFalse(policy.denies("demo_order"));
        assertFalse(policy.denies("supply_order_item"));
        assertFalse(policy.denies("payment"));
    }

    @Test
    @DisplayName("配置里的引号与空白会被清掉，避免「配了却没生效」")
    void normalizesConfiguredNames() {
        GuardPolicy policy = new GuardPolicy(0, List.of("  `supply_order`  ", "\"PAYMENT\""), false);

        assertTrue(policy.denies("supply_order"));
        assertTrue(policy.denies("payment"));
        assertTrue(policy.describe().contains("supply_order"), policy.describe());
    }

    @Test
    @DisplayName("空黑名单时不匹配任何东西，也不因 null 抛异常")
    void emptyDenylist() {
        GuardPolicy policy = GuardPolicy.passThrough();

        assertFalse(policy.denies("anything"));
        assertFalse(policy.denies(null));
        assertFalse(policy.denies(""));
    }

    @Test
    @DisplayName("三项都没配时就是直通，starter 据此不包装 DataSource")
    void passThroughDetection() {
        assertTrue(GuardPolicy.passThrough().nothingToEnforce());
        assertFalse(new GuardPolicy(1, List.of(), false).nothingToEnforce());
        assertFalse(new GuardPolicy(0, List.of("t"), false).nothingToEnforce());
        assertFalse(new GuardPolicy(0, List.of(), true).nothingToEnforce());
    }

    @Test
    @DisplayName("行数上限 0 或负数等于不限")
    void rowLimitSwitch() {
        assertFalse(new GuardPolicy(0, List.of(), false).rowLimitEnabled());
        assertFalse(new GuardPolicy(-1, List.of(), false).rowLimitEnabled());
        assertTrue(new GuardPolicy(1, List.of(), false).rowLimitEnabled());
    }

    @Test
    @DisplayName("描述文字要能直接放进启动日志")
    void describe() {
        assertEquals("none (pass-through)", GuardPolicy.passThrough().describe());
        assertEquals("rows<=1000, deny=[supply_order], ddl=deny",
                new GuardPolicy(1000, List.of("supply_order"), true).describe());
    }

    @Test
    @DisplayName("负数上限小于 -1 视为配置错误，直接拒绝而不是静默修正")
    void rejectsInvalidLimit() {
        assertThrows(IllegalArgumentException.class, () -> new GuardPolicy(-5, List.of(), false));
    }
}
