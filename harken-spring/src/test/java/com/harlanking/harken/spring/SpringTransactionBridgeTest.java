package com.harlanking.harken.spring;

import com.harlanking.harken.core.spi.TransactionBridge;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务桥的行为约定。
 *
 * <p>重点验证两件事：默认传播行为是 {@code REQUIRES_NEW}（不污染调用方事务），
 * 只读执行一定走 rollback 而不是 commit。
 */
class SpringTransactionBridgeTest {

    @Test
    void readOnlyExecutionRollsBackInsteadOfCommitting() {
        RecordingTxManager manager = new RecordingTxManager();
        SpringTransactionBridge bridge = new SpringTransactionBridge(manager);

        String value = bridge.inTransaction(TransactionBridge.Mode.READ_ONLY, () -> "done");

        assertEquals("done", value);
        assertEquals(List.of("begin(ro=true,prop=3)", "rollback"), manager.events);
    }

    @Test
    void rollbackModeAllowsWritesButNeverCommits() {
        RecordingTxManager manager = new RecordingTxManager();
        SpringTransactionBridge bridge = new SpringTransactionBridge(manager);

        bridge.inTransaction(TransactionBridge.Mode.ROLLBACK, () -> null);

        // ro=false 是关键：设成 true 时 MySQL/PG 会让 UPDATE 直接失败，试跑就试不了
        assertEquals(List.of("begin(ro=false,prop=3)", "rollback"), manager.events);
    }

    @Test
    void commitModeCommits() {
        RecordingTxManager manager = new RecordingTxManager();
        SpringTransactionBridge bridge = new SpringTransactionBridge(manager);

        bridge.inTransaction(TransactionBridge.Mode.COMMIT, () -> null);

        assertEquals(List.of("begin(ro=false,prop=3)", "commit"), manager.events);
    }

    @Test
    void usesRequiresNewByDefault() {
        RecordingTxManager manager = new RecordingTxManager();
        SpringTransactionBridge bridge = new SpringTransactionBridge(manager);

        bridge.inTransaction(TransactionBridge.Mode.READ_ONLY, () -> null);

        // REQUIRES_NEW = 3。若变成 REQUIRED(0)，脚本里的 setRollbackOnly 会污染调用方事务
        assertTrue(manager.events.get(0).contains("prop=" + TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void nullManagerDegradesToNoop() {
        TransactionBridge bridge = SpringTransactionBridge.ofNullable(null);

        assertFalse(bridge.isSupported());
        assertEquals("ok", bridge.inTransaction(TransactionBridge.Mode.READ_ONLY, () -> "ok"));
        assertEquals("none (no transaction protection)", bridge.description());
    }

    @Test
    void noopBridgeRefusesRollbackAndCommit() {
        TransactionBridge bridge = SpringTransactionBridge.ofNullable(null);

        assertThrows(IllegalStateException.class,
                () -> bridge.inTransaction(TransactionBridge.Mode.ROLLBACK, () -> null));
        assertThrows(IllegalStateException.class,
                () -> bridge.inTransaction(TransactionBridge.Mode.COMMIT, () -> null));
    }

    @Test
    void descriptionMentionsRealManagerAndPropagation() {
        SpringTransactionBridge bridge = new SpringTransactionBridge(new RecordingTxManager());

        assertTrue(bridge.isSupported());
        assertTrue(bridge.description().contains("RecordingTxManager"));
        assertTrue(bridge.description().contains("REQUIRES_NEW"));
    }
}
