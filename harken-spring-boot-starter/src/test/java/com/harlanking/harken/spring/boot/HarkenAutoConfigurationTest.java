package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.HarkenRuntime;
import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.impl.MemoryAuditStore;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.metadata.ClassIndex;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动配置的行为约定：默认启用、属性绑定、可关闭、可被用户覆盖。
 */
class HarkenAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HarkenAutoConfiguration.class));

    @Test
    void registersServiceIndexAndReporterByDefault() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBean("harkenService"));
            assertTrue(context.containsBean("harkenClassIndex"));
            assertTrue(context.containsBean("harkenStartupReporter"));
            assertNotNull(context.getBean(ClassIndex.class));
        });
    }

    @Test
    void noTransactionManagerMeansWritesAreRejected() {
        runner.run(context -> {
            HarkenService service = context.getBean(HarkenService.class);
            // 没有事务保护时，UI 与启动日志都必须能看出这件事
            assertFalse(service.transactionSupported());
            assertEquals("none (no transaction protection)", service.transactionDescription());
        });
    }

    @Test
    void bindsPropertiesIntoOptions() {
        runner.withPropertyValues("harken.timeout-ms=1234", "harken.packages=com.zm")
                .run(context -> {
                    HarkenProperties properties = context.getBean(HarkenProperties.class);
                    assertEquals(1234L, properties.getTimeoutMs());
                    assertEquals(List.of("com.zm"), properties.getPackages());
                    assertEquals(1234L, context.getBean(HarkenService.class).options().timeoutMs());
                });
    }

    @Test
    void canBeDisabledEntirely() {
        runner.withPropertyValues("harken.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("harkenService"));
                    assertFalse(context.containsBean("harkenMetadataScanner"));
                });
    }

    @Test
    void backsOffWhenUserDeclaresOwnService() {
        HarkenService custom = HarkenRuntime.builder().build();
        runner.withBean(HarkenService.class, () -> custom)
                .run(context -> assertSame(custom, context.getBean(HarkenService.class)));
    }

    @Test
    void auditDescriptionIsNeverEmpty() {
        runner.run(context -> {
            String audit = context.getBean(HarkenService.class).auditDescription();
            // 默认必须是 memory + slf4j（计划 §8.1）：UI 能看历史，全量落日志，零建表
            assertTrue(audit.contains("Slf4jAuditStore"), audit);
            assertTrue(audit.contains("MemoryAuditStore"), audit);
        });
    }

    @Test
    void memoryAuditIsOnByDefaultAndQueryable() {
        runner.run(context -> {
            assertTrue(context.containsBean("harkenMemoryAuditStore"),
                    "默认挂内存审计：控制台的「最近执行」面板靠它，零配置可用");

            HarkenService service = context.getBean(HarkenService.class);
            // 执行一次就能反查 —— 这是 M3 控制台的地基
            service.execute(new ReadOnlyRequest("return 42"), "harlan", "127.0.0.1");
            assertEquals(1, service.recentAudits(10).size());
            assertEquals("harlan", service.recentAudits(10).get(0).operator());
        });
    }

    @Test
    void memoryAuditCanBeTurnedOffButLogsStay() {
        runner.withPropertyValues("harken.audit.memory.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("harkenMemoryAuditStore"));
                    // 关掉内存后必须仍然有日志审计，否则等于没有审计
                    assertEquals("Slf4jAuditStore",
                            context.getBean(HarkenService.class).auditDescription());
                    assertTrue(context.getBean(HarkenService.class).recentAudits(10).isEmpty(),
                            "没有可查询的后端时返回空列表，不抛异常");
                });
    }

    @Test
    void memoryAuditHonoursConfiguredCapacityAndKeepsSlf4j() {
        runner.withPropertyValues("harken.audit.memory.capacity=3")
                .run(context -> {
                    HarkenService service = context.getBean(HarkenService.class);
                    String audit = service.auditDescription();
                    assertTrue(audit.contains("MemoryAuditStore"), audit);
                    assertTrue(audit.contains("Slf4jAuditStore"),
                            "内存不能当证据，日志一路必须还在：" + audit);
                    assertEquals(3, context.getBean(MemoryAuditStore.class).capacity());
                });
    }
}
