package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.spi.WriteGuard;
import com.harlanking.harken.jdbc.GuardedDataSource;
import com.harlanking.harken.jdbc.JdbcWriteGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;

/**
 * 把容器里的每个 {@link DataSource} 套上写入守卫的代理。
 *
 * <h2>为什么用 BeanPostProcessor</h2>
 *
 * <p>脚本调的是业务 Bean，业务 Bean 用的是 {@code JdbcTemplate} / MyBatis，
 * 它们在构造时就抓住了 {@code DataSource}。要让行数闸生效，只能在数据源被注入给
 * 别人之前换掉它 —— 这正是 {@code BeanPostProcessor} 的职责，也是唯一不需要
 * 使用者改一行代码的地方。
 *
 * <h2>三条自我保护</h2>
 * <ol>
 *   <li><b>没配策略就不包装。</b> 包装意味着全应用的 SQL 多走一层反射代理，
 *       没有明确需求时不该承担这个风险（见 {@code GuardPolicy#nothingToEnforce()}）。</li>
 *   <li><b>不重复包装。</b> 否则行数会翻倍，行数闸会在「看起来只改了 500 行」时炸掉。</li>
 *   <li><b>守卫用 {@link ObjectProvider} 延迟取。</b> 在 {@code BeanPostProcessor}
 *       构造时就把带 {@code @ConfigurationProperties} 的 Bean 拉起来，
 *       会绕过属性绑定，导致读到的全是默认值 —— 配置了黑名单却完全不生效，
 *       这是最难查的一类问题。</li>
 * </ol>
 *
 * <h2>代价（必须说清楚）</h2>
 *
 * <p>包装后容器里的 {@code DataSource} Bean 不再是 {@code HikariDataSource} 之类的具体类型。
 * 如果某个 Bean 用具体类型注入（{@code @Autowired HikariDataSource}），会启动失败。
 * 这时要么改成注入 {@code DataSource}，要么用 {@link GuardedDataSource#unwrap}
 * 取回原始对象 —— 但取回原始对象就等于放弃守卫。
 *
 * @author harlan
 * @since 1.0.0
 */
public class HarkenDataSourceGuardPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HarkenDataSourceGuardPostProcessor.class);

    private final ObjectProvider<WriteGuard> guardProvider;

    /**
     * @param guardProvider 守卫提供者，延迟解析
     */
    public HarkenDataSourceGuardPostProcessor(ObjectProvider<WriteGuard> guardProvider) {
        this.guardProvider = guardProvider;
    }

    /**
     * @param bean     刚初始化完的 Bean
     * @param beanName Bean 名
     * @return 包装后的数据源，或原对象
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource dataSource) || GuardedDataSource.isGuarded(dataSource)) {
            return bean;
        }
        JdbcWriteGuard guard = resolveGuard();
        if (guard == null || guard.policy().nothingToEnforce()) {
            return bean;
        }
        log.info("Harken: 已为数据源 {} 启用写入守卫（{}）", beanName, guard.description());
        return GuardedDataSource.wrap(dataSource, guard);
    }

    private JdbcWriteGuard resolveGuard() {
        try {
            WriteGuard guard = guardProvider.getIfAvailable();
            return guard instanceof JdbcWriteGuard jdbc ? jdbc : null;
        } catch (RuntimeException e) {
            // 守卫建不出来不该让应用起不来：没有守卫只是少了行数闸，不是致命错误
            log.warn("Harken: 写入守卫不可用，数据源未被包装（行数闸与表黑名单不生效）", e);
            return null;
        }
    }

    /** @return 便于日志识别 */
    @Override
    public String toString() {
        return "HarkenDataSourceGuardPostProcessor";
    }
}
