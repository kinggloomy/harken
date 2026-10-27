package com.harlanking.harken.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件权限检查：结论必须反映事实，宁可不判断也不能瞎判断。
 *
 * <p>POSIX 相关的用例只在类 Unix 上跑 —— 它们的意义正是「Windows 上没有这个模型」，
 * 所以另有一组无条件用例专门验证 Windows 会得到 {@code UNSUPPORTED}。
 */
class ConfigFilePermissionsTest {

    @Test
    void nullPathIsMissingNotAnException() {
        ConfigFilePermissions.Result r = ConfigFilePermissions.check(null);
        assertEquals(ConfigFilePermissions.Status.MISSING, r.status());
        assertFalse(r.shouldWarn());
        assertNotNull(r.detail());
    }

    @Test
    void absentFileIsMissing(@TempDir Path dir) {
        ConfigFilePermissions.Result r = ConfigFilePermissions.check(dir.resolve("nope.yml"));
        assertEquals(ConfigFilePermissions.Status.MISSING, r.status());
        assertEquals("----", r.octal(), "没读到权限时不能编一个数字出来");
    }

    @Test
    void directoryIsNotAFile(@TempDir Path dir) {
        ConfigFilePermissions.Result r = ConfigFilePermissions.check(dir);
        assertEquals(ConfigFilePermissions.Status.NOT_A_FILE, r.status());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sixHundredIsOk(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"), "harken:\n  enabled: true\n");
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        ConfigFilePermissions.Result r = ConfigFilePermissions.check(file);

        assertEquals(ConfigFilePermissions.Status.OK, r.status(), r.detail());
        assertEquals("600", r.octal());
        assertFalse(r.shouldWarn());
        assertTrue(r.measured());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sixFourFourIsTooOpen(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"), "harken:\n  ai:\n    token: secret\n");
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));

        ConfigFilePermissions.Result r = ConfigFilePermissions.check(file);

        assertEquals(ConfigFilePermissions.Status.TOO_OPEN, r.status());
        assertEquals("644", r.octal());
        assertTrue(r.shouldWarn());
        // 提示要能直接告诉人怎么改，而不是只说"权限不对"
        assertTrue(r.detail().contains("group:READ"), r.detail());
        assertTrue(r.detail().contains("others:READ"), r.detail());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void groupWriteAloneIsEnoughToWarn(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.properties"), "harken.enabled=true\n");
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE));

        ConfigFilePermissions.Result r = ConfigFilePermissions.check(file);

        assertEquals(ConfigFilePermissions.Status.TOO_OPEN, r.status());
        assertEquals("620", r.octal());
        assertTrue(r.detail().contains("group:WRITE"), r.detail());
    }

    @Test
    void octalConversionIsStable() {
        assertEquals("600", ConfigFilePermissions.toOctal(EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        assertEquals("777", ConfigFilePermissions.toOctal(EnumSet.allOf(PosixFilePermission.class)));
        assertEquals("000", ConfigFilePermissions.toOctal(Set.of()));
        assertEquals("000", ConfigFilePermissions.toOctal(null));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsReportsUnsupportedInsteadOfOk(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yml"), "harken:\n  enabled: true\n");

        ConfigFilePermissions.Result r = ConfigFilePermissions.check(file);

        // 关键：不能返回 OK。Windows 上根本没做检查，说"没问题"就是撒谎
        assertEquals(ConfigFilePermissions.Status.UNSUPPORTED, r.status());
        assertFalse(r.shouldWarn());
        assertFalse(r.measured());
        assertEquals("----", r.octal());
    }
}
