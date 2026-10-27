package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.ConfigFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从 Spring 的 origin 里找出「哪个文件带着 Harken 配置」。
 *
 * <p>这里用真实的 {@link OriginTrackedMapPropertySource} 造一个属性源，
 * 而不是 mock —— 要验证的正是「Boot 的 origin 机制能被我读到」，
 * mock 掉它就等于什么都没验。
 */
class HarkenConfigFileCheckTest {

    /** 造一个带 origin 的属性源，模拟 Boot 从 yml 加载出来的样子。 */
    private static OriginTrackedMapPropertySource originTracked(String name, Path file, String key) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, OriginTrackedValue.of("dummy", new TextResourceOrigin(new FileSystemResource(file), null)));
        return new OriginTrackedMapPropertySource(name, values);
    }

    @Test
    void findsFileBehindHarkenProperties(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"), "harken:\n  enabled: true\n");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(originTracked("config", file, "harken.web.users[0].password"));

        List<Path> files = HarkenConfigFileCheck.files(env);

        assertEquals(1, files.size());
        assertEquals(file.toAbsolutePath(), files.get(0));
    }

    @Test
    void sameFileIsReportedOnce(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"), "harken:\n  enabled: true\n");
        Map<String, Object> values = new LinkedHashMap<>();
        TextResourceOrigin origin = new TextResourceOrigin(new FileSystemResource(file), null);
        values.put("harken.ai.token", OriginTrackedValue.of("t", origin));
        values.put("harken.web.users[0].password", OriginTrackedValue.of("p", origin));
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new OriginTrackedMapPropertySource("config", values));

        assertEquals(1, HarkenConfigFileCheck.files(env).size(), "一个文件里多个键，只检查一次");
    }

    @Test
    void ignoresNonHarkenKeys(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"), "spring:\n  profiles: {}\n");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(originTracked("config", file, "spring.datasource.url"));

        assertTrue(HarkenConfigFileCheck.files(env).isEmpty(),
                "别人的配置项不归 Harken 管，不该被拉进来检查");
    }

    @Test
    void skipsPropertySourcesWithoutOrigin(@TempDir Path dir) {
        StandardEnvironment env = new StandardEnvironment();
        // 命令行参数、系统属性这类来源没有文件 origin —— 不该猜一个路径出来
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("harken.enabled", "true")));

        assertTrue(HarkenConfigFileCheck.files(env).isEmpty());
    }

    @Test
    void toleratesNullAndNonConfigurableEnvironment() {
        assertTrue(HarkenConfigFileCheck.files(null).isEmpty());
        assertTrue(HarkenConfigFileCheck.check(null).isEmpty());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void tooOpenFileIsFlaggedEndToEnd(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"),
                "harken:\n  web:\n    users:\n      - password: secret\n");
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OTHERS_READ));

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(originTracked("config", file, "harken.web.users[0].password"));

        List<ConfigFilePermissions.Result> results = HarkenConfigFileCheck.check(env);

        assertEquals(1, results.size());
        assertTrue(results.get(0).shouldWarn());
        assertEquals("604", results.get(0).octal());
        assertTrue(results.get(0).detail().contains("others:READ"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sixHundredProducesNoWarning(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"), "harken:\n  enabled: true\n");
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(originTracked("config", file, "harken.enabled"));

        ConfigFilePermissions.Result result = HarkenConfigFileCheck.check(env).get(0);

        assertFalse(result.shouldWarn());
        assertEquals(ConfigFilePermissions.Status.OK, result.status());
    }

    @Test
    void resolvesPathResourceOriginWhichIsWhatBootActuallyUses(@TempDir Path dir) throws IOException {
        // Boot 3 的 config data 用的就是 PathResource。第一版实现按
        // FileSystemResource / UrlResource 两个类型判断，结果在真实应用里
        // 静默失效（本地单测用的是 FileSystemResource，所以是绿的）。
        Path file = Files.writeString(dir.resolve("application.yaml"), "harken:\n  enabled: true\n");
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("harken.enabled", OriginTrackedValue.of("true",
                new TextResourceOrigin(new org.springframework.core.io.PathResource(file), null)));
        env.getPropertySources().addFirst(new OriginTrackedMapPropertySource("config", values));

        assertEquals(List.of(file.toAbsolutePath()), HarkenConfigFileCheck.files(env));
    }

    @Test
    void ignoresClassPathOrigin() {
        // 配置打在 jar 里时，origin 是 classpath 资源 —— 没有可检查的文件，静静跳过
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("harken.enabled", OriginTrackedValue.of("true",
                new TextResourceOrigin(
                        new org.springframework.core.io.ClassPathResource("application.yml"), null)));
        env.getPropertySources().addFirst(new OriginTrackedMapPropertySource("config", values));

        assertTrue(HarkenConfigFileCheck.files(env).isEmpty());
    }

    @Test
    void discoversTheFileLoadedBySpringBootItself(@TempDir Path dir) throws IOException {
        // 用 Boot 真正的加载器，而不是手搓属性源 —— 要验证的就是
        // 「Boot 加载出来的东西能被读到」。这里用 .properties，因为 starter 的
        // 测试类路径上没有 snakeyaml，而 .properties 走的是同一条 origin 追踪路径。
        Path file = Files.writeString(dir.resolve("application.properties"),
                "harken.web.users[0].password=secret\n");

        StandardEnvironment env = new StandardEnvironment();
        for (var source : new org.springframework.boot.env.PropertiesPropertySourceLoader()
                .load("application.properties", new FileSystemResource(file))) {
            env.getPropertySources().addFirst(source);
        }

        assertEquals(List.of(file.toAbsolutePath()), HarkenConfigFileCheck.files(env));
    }
}
