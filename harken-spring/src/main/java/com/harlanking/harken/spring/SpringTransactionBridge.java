package com.harlanking.harken.spring;

import com.harlanking.harken.core.spi.TransactionBridge;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import java.util.function.Supplier;

/**
 * 用 Spring 的 {@link PlatformTransactionManager} 实现事务桥。
 *
 * <h2>两个关键选择</h2>
 *
 * <p><b>① 默认传播行为是 {@code REQUIRES_NEW}，不是 {@code REQUIRED}。</b>
 * 如果脚本被某个已有事务调用（例如从 HTTP 层进来时外层已经开了事务），
 * {@code REQUIRED} 会加入外层事务，脚本里的 {@code setRollbackOnly()} 会把
 * <b>外层业务事务一起标记为回滚</b>，甚至让一个本来只想「试试看」的只读脚本
 * 把调用方的提交搞掉。{@code REQUIRES_NEW} 挂起外层、开独立事务，语义清晰。
 *
 * <p><b>② 只读不是「不写代码」，而是「连接只读 + 一定回滚」。</b>
 * {@code setReadOnly(true)} 让数据库层面拒绝写入（MySQL / PostgreSQL 会真的把
 * 会话或事务置为只读）；{@code status.setRollbackOnly()} 保证无论如何都不提交。
 * 两者都做：前者能拦住误写，后者兜住所有回滚。
 *
 * <p><b>但试跑（{@code ROLLBACK}）不能设 {@code readOnly}。</b> 试跑要的是
 * 「写进去、看影响行数、然后撤掉」，一旦把连接设成只读，UPDATE 会直接报
 * {@code cannot execute UPDATE in a read-only transaction}，功能就没了。
 * 所以这里严格按 {@link Mode} 映射，不做「都是不提交，那就都设只读」的简化。
 *
 * <p>这里依赖 Spring 的既有契约：{@code TransactionTemplate} 会无条件调用
 * {@code commit(status)}，由 {@code PlatformTransactionManager} 检查 rollback-only
 * 并转为回滚。所有 Spring 官方实现（继承 {@code AbstractPlatformTransactionManager}）
 * 都满足这条契约。若使用者自己实现了 {@code PlatformTransactionManager} 且不检查
 * rollback-only，只读保证就不成立 —— 这类实现本身也违反了 Spring 的事务约定。
 *
 * <p>本类无状态、线程安全，可作为单例复用。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class SpringTransactionBridge implements TransactionBridge {

    private final PlatformTransactionManager transactionManager;
    private final int propagationBehavior;

    /**
     * 使用 {@code REQUIRES_NEW} 传播行为。
     *
     * @param transactionManager 事务管理器，不能为 {@code null}
     */
    public SpringTransactionBridge(PlatformTransactionManager transactionManager) {
        this(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param transactionManager  事务管理器，不能为 {@code null}
     * @param propagationBehavior Spring 传播行为常量
     */
    public SpringTransactionBridge(PlatformTransactionManager transactionManager, int propagationBehavior) {
        this.transactionManager = transactionManager;
        this.propagationBehavior = propagationBehavior;
    }

    /**
     * 空安全的工厂：没有事务管理器时返回
     * {@link TransactionBridge.NoopTransactionBridge}，而不是抛异常。
     *
     * <p>这个语义很重要：应用可能根本没配数据库。此时 Harken 应该照样能启动、
     * 照样能跑只读脚本，只是写操作会被拒绝（SR-02）。
     *
     * @param transactionManager 可为 {@code null}
     * @return 可用的桥
     */
    public static TransactionBridge ofNullable(PlatformTransactionManager transactionManager) {
        return transactionManager == null
                ? TransactionBridge.NoopTransactionBridge.INSTANCE
                : new SpringTransactionBridge(transactionManager);
    }

    /**
     * 从容器里自动探测事务桥。优先用用户自己注册的 {@link TransactionBridge} Bean，
     * 其次找唯一的 {@link PlatformTransactionManager}，都没有则返回无事务桥。
     *
     * @param context Spring 容器，不能为 {@code null}
     * @return 可用的桥，永不返回 {@code null}
     */
    public static TransactionBridge detect(ApplicationContext context) {
        TransactionBridge custom = context.getBeanProvider(TransactionBridge.class).getIfUnique();
        if (custom != null) {
            return custom;
        }
        PlatformTransactionManager manager =
                context.getBeanProvider(PlatformTransactionManager.class).getIfUnique();
        return ofNullable(manager);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T inTransaction(Mode mode, Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagationBehavior);
        // 只有严格只读才把连接置只读；试跑要写，置只读会让 UPDATE 直接失败
        template.setReadOnly(mode == Mode.READ_ONLY);
        return template.execute(status -> {
            if (mode != Mode.COMMIT) {
                // 只读与试跑的唯一区别在数据库层，结束动作都是回滚
                status.setRollbackOnly();
            }
            return work.get();
        });
    }

    /** {@inheritDoc} */
    @Override
    public boolean isSupported() {
        return transactionManager != null;
    }

    /**
     * {@inheritDoc}
     *
     * @return 形如 {@code spring (JdbcTransactionManager, REQUIRES_NEW)}
     */
    @Override
    public String description() {
        if (transactionManager == null) {
            return "none (no transaction protection)";
        }
        return "spring (" + ClassUtils.getUserClass(transactionManager).getSimpleName()
                + ", " + propagationName(propagationBehavior) + ")";
    }

    private static String propagationName(int behavior) {
        return switch (behavior) {
            case TransactionDefinition.PROPAGATION_REQUIRED -> "REQUIRED";
            case TransactionDefinition.PROPAGATION_REQUIRES_NEW -> "REQUIRES_NEW";
            case TransactionDefinition.PROPAGATION_SUPPORTS -> "SUPPORTS";
            case TransactionDefinition.PROPAGATION_NOT_SUPPORTED -> "NOT_SUPPORTED";
            case TransactionDefinition.PROPAGATION_MANDATORY -> "MANDATORY";
            case TransactionDefinition.PROPAGATION_NEVER -> "NEVER";
            case TransactionDefinition.PROPAGATION_NESTED -> "NESTED";
            default -> "PROPAGATION_" + behavior;
        };
    }
}
