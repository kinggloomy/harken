package com.harlanking.harken.spring;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bean 解析器的三条核心约定：懒加载不被破坏、框架 Bean 被过滤、包白名单生效。
 */
class SpringBeanResolverTest {

    private static final AtomicInteger SLOW_INIT = new AtomicInteger();

    @Configuration
    public static class Config {

        @Bean
        public OrderService orderService() {
            return new OrderService();
        }

        @Bean
        @Lazy
        public SlowService slowService() {
            SLOW_INIT.incrementAndGet();
            return new SlowService();
        }
    }

    /** 普通业务 Bean。 */
    public static class OrderService {
        public long count() {
            return 42;
        }
    }

    /** 启动时不应被创建的 Bean。 */
    public static class SlowService {
        public String ping() {
            return "pong";
        }
    }

    @Test
    void scanningNeverInstantiatesLazyBeans() {
        SLOW_INIT.set(0);
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Config.class)) {

            SpringBeanResolver resolver = new SpringBeanResolver(context);

            assertTrue(resolver.contains("orderService"));
            assertTrue(resolver.contains("slowService"));
            assertNotNull(resolver.typeOf("slowService"));

            // 核心断言：contains / typeOf 都不能触发实例化。
            // 一旦这里变成 1，说明有人把 getType 改成了 getBean，启动会炸一片 @PostConstruct。
            assertEquals(0, SLOW_INIT.get());

            assertNotNull(resolver.get("slowService"));
            assertEquals(1, SLOW_INIT.get());
        }
    }

    @Test
    void filtersFrameworkBeansByDefault() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Config.class)) {

            SpringBeanResolver resolver = new SpringBeanResolver(context);

            assertFalse(resolver.contains("environment"));
            assertFalse(resolver.contains("systemProperties"));
            assertTrue(resolver.names().stream().noneMatch(n -> n.startsWith("org.springframework")));
        }
    }

    @Test
    void packageWhitelistExcludesEverythingElse() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Config.class)) {

            SpringBeanResolver none = new SpringBeanResolver(context, List.of("com.zm.not.exist"));
            assertEquals(0, none.size());

            SpringBeanResolver mine = new SpringBeanResolver(context,
                    List.of("com.harlanking.harken.spring"));
            assertTrue(mine.contains("orderService"));
        }
    }

    @Test
    void unknownBeanResolvesToNullInsteadOfThrowing() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Config.class)) {

            SpringBeanResolver resolver = new SpringBeanResolver(context);

            assertFalse(resolver.contains("noSuchBean"));
            assertEquals(null, resolver.get("noSuchBean"));
            assertEquals(null, resolver.typeOf("noSuchBean"));
        }
    }
}
