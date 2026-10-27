package com.harlanking.harken.spring;

import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.metadata.MethodCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证：Spring 容器里的 Bean 能被脚本直接调用，事务语义与两条通道的边界都成立。
 */
class SpringHarkenIntegrationTest {

    @Configuration
    public static class TxConfig {

        @Bean
        public OrderService orderService() {
            return new OrderService();
        }

        @Bean
        public PlatformTransactionManager transactionManager() {
            return new RecordingTxManager();
        }
    }

    @Configuration
    public static class NoTxConfig {

        @Bean
        public OrderService orderService() {
            return new OrderService();
        }
    }

    /** 业务 Bean。脚本里直接用 Bean 名 {@code orderService} 访问。 */
    public static class OrderService {

        public long count() {
            return 42;
        }

        public String describe(String prefix) {
            return prefix + "-order";
        }
    }

    @Test
    void scriptCallsBeanDirectlyAndRollsBackByDefault() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TxConfig.class)) {

            HarkenService service = SpringHarken.service(context,
                    List.of("com.harlanking.harken.spring"), null);

            ScriptResult result = service.execute(
                    new ReadWriteRequest("println 'hi'\nreturn orderService.count()", false),
                    "tester", "127.0.0.1");

            assertTrue(result.ok(), result.error());
            assertEquals("42", result.result());
            assertTrue(result.output().contains("hi"));
            assertTrue(result.rolledBack());
            assertEquals("human", result.channel());
        }
    }

    @Test
    void commitCommitsWhenTransactionManagerPresent() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TxConfig.class)) {

            RecordingTxManager manager = context.getBean(RecordingTxManager.class);
            HarkenService service = SpringHarken.service(context,
                    List.of("com.harlanking.harken.spring"), null);

            ScriptResult result = service.execute(
                    new ReadWriteRequest("return orderService.describe('vip')", true),
                    "tester", "127.0.0.1");

            assertTrue(result.ok(), result.error());
            assertEquals("vip-order", result.result());
            assertFalse(result.rolledBack());
            assertTrue(manager.events.contains("commit"));
            assertFalse(manager.events.contains("rollback"));
        }
    }

    @Test
    void aiChannelIsRejectedWhenNoTransactionProtection() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(NoTxConfig.class)) {

            HarkenService service = SpringHarken.service(context,
                    List.of("com.harlanking.harken.spring"), null);

            // 没有事务时，bean 方法内部的写入无法回滚，「只读」是假的安全承诺
            ScriptResult ai = service.execute(new ReadOnlyRequest("return orderService.count()"));
            assertFalse(ai.ok());
            assertEquals("HARKEN_AI_READONLY_UNSAFE", ai.errorType());

            // 人类通道的写操作同样被拒绝，但要给出不同的错误码，便于 UI 分别提示
            ScriptResult human = service.execute(
                    new ReadWriteRequest("return orderService.count()", true), "tester", "127.0.0.1");
            assertFalse(human.ok());
            assertEquals("HARKEN_NO_TRANSACTION", human.errorType());
        }
    }

    @Test
    void aiChannelWorksWhenTransactionIsAvailable() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TxConfig.class)) {

            HarkenService service = SpringHarken.service(context,
                    List.of("com.harlanking.harken.spring"), null);

            ScriptResult result = service.execute(new ReadOnlyRequest("return orderService.count()"));

            assertTrue(result.ok(), result.error());
            assertEquals("42", result.result());
            assertEquals("ai", result.channel());
            assertTrue(result.rolledBack());
        }
    }

    @Test
    void classIndexExposesBusinessClassesForCompletion() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TxConfig.class)) {

            ClassIndex index = SpringHarken.classIndex(context, List.of("com.harlanking.harken.spring"));

            // import 补全要靠这张表：包名 → 类简名
            assertTrue(index.byPackage()
                    .getOrDefault("com.harlanking.harken.spring", List.of())
                    .contains("SpringHarken"), index.byPackage().toString());
        }
    }

    @Test
    void methodCatalogServesBeansAndStaticTypes() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TxConfig.class)) {

            HarkenService service = SpringHarken.service(context,
                    List.of("com.harlanking.harken.spring"), null);
            MethodCatalog catalog = new MethodCatalog(service.beanResolver());

            assertTrue(catalog.beanTypes().containsKey("orderService"));
            assertTrue(catalog.instanceMethods("orderService").stream()
                    .anyMatch(m -> m.name().equals("count")));
            // 静态方法不依赖容器，任意全限定名都能读
            assertTrue(catalog.staticMethods("com.harlanking.harken.spring.SpringHarken").stream()
                    .anyMatch(m -> m.name().equals("service")));
        }
    }

    @Test
    void transactionDescriptionIsVisibleForUiBanner() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TxConfig.class)) {

            HarkenService service = SpringHarken.service(context,
                    List.of("com.harlanking.harken.spring"), null);

            assertTrue(service.transactionSupported());
            assertTrue(service.transactionDescription().contains("REQUIRES_NEW"));
        }
    }
}
