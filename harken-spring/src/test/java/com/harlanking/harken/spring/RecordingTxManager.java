package com.harlanking.harken.spring;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试替身：记录事务调用轨迹，不连接任何数据库。
 *
 * <p>用它而不是真实 {@code DataSourceTransactionManager}，才能在没有 DB 的情况下
 * 断言「默认传播行为是 REQUIRES_NEW」「只读一定 rollback」这两条契约。
 */
final class RecordingTxManager implements PlatformTransactionManager {

    final List<String> events = new ArrayList<>();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        events.add("begin(ro=" + definition.isReadOnly()
                + ",prop=" + definition.getPropagationBehavior() + ")");
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        // 真实实现（AbstractPlatformTransactionManager#processCommit）会先看 rollback-only，
        // 命中就转成 rollback。测试替身必须复刻这一契约，否则会验不出只读语义。
        events.add(status.isRollbackOnly() ? "rollback" : "commit");
    }

    @Override
    public void rollback(TransactionStatus status) {
        events.add("rollback");
    }
}
