package com.harlanking.demo.order;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一个<b>不由 Spring 管理</b>的类：私有构造器、全是静态方法。
 *
 * <p>它存在的意义是演示补全的第三条路径 —— 在编辑器里敲
 * <pre>{@code
 * import com.harlanking.demo.order.
 * }</pre>
 * 就能看到它；敲 {@code MoneyUtil.} 就能看到下面这些方法。
 * 工具类不需要进容器，也就不必为了「能不能被提示到」而把它包成 Bean。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class MoneyUtil {

    private MoneyUtil() {
    }

    /**
     * 分转元，保留两位小数。
     *
     * @param cents 金额（分）
     * @return 金额（元）
     */
    public static BigDecimal centsToYuan(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 元转分，四舍五入到整分。
     *
     * @param yuan 金额（元）
     * @return 金额（分）
     */
    public static long yuanToCents(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 按折扣率打折。
     *
     * @param amount 原价
     * @param rate   折扣率，{@code 0.85} 表示八五折
     * @return 折后价，保留两位小数
     */
    public static BigDecimal applyDiscount(BigDecimal amount, double rate) {
        return amount.multiply(BigDecimal.valueOf(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 格式化成带千分位的显示文本。
     *
     * @param amount 金额
     * @return 例如 {@code 1,234.50}
     */
    public static String format(BigDecimal amount) {
        return String.format("%,.2f", amount);
    }
}
