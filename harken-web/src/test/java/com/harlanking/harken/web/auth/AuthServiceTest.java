package com.harlanking.harken.web.auth;

import com.harlanking.harken.web.HarkenWebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证中心：登录、锁定、重载、AI Token。
 */
class AuthServiceTest {

    private static String fastHash(String plain) {
        return PasswordHash.hash(plain, 1_000);
    }

    private static HarkenWebConfig.Builder baseConfig() {
        return HarkenWebConfig.builder()
                .generatePasswordIfNoUsers(false)
                .maxLoginFailures(3)
                .lockMinutes(1);
    }

    @Test
    @DisplayName("哈希密码登录成功，角色随会话带回")
    void loginWithHashedPassword() {
        AuthService auth = new AuthService(baseConfig()
                .users(List.of(new HarkenWebConfig.UserSpec("harlan", fastHash("pw123"), "ADMIN")))
                .build());

        AuthService.LoginResult r = auth.login("harlan", "pw123");

        assertTrue(r.ok());
        assertNotNull(r.session());
        assertEquals(Role.ADMIN, r.session().role());
        assertEquals(1, auth.userCount());
    }

    @Test
    @DisplayName("明文密码也能登录（会在启动日志里 WARN）")
    void loginWithPlaintextPassword() {
        AuthService auth = new AuthService(baseConfig()
                .users(List.of(new HarkenWebConfig.UserSpec("ops", "plain-pw", "OPERATOR")))
                .build());

        assertTrue(auth.login("ops", "plain-pw").ok());
    }

    @Test
    @DisplayName("密码错误与用户不存在返回同一个状态，不泄漏用户是否存在")
    void badPasswordAndUnknownUserLookTheSame() {
        AuthService auth = new AuthService(baseConfig()
                .users(List.of(new HarkenWebConfig.UserSpec("harlan", fastHash("pw"), "ADMIN")))
                .build());

        AuthService.LoginResult wrongPw = auth.login("harlan", "nope");
        AuthService.LoginResult unknown = auth.login("nobody", "nope");

        assertEquals(AuthService.LoginStatus.BAD_CREDENTIALS, wrongPw.status());
        assertEquals(AuthService.LoginStatus.BAD_CREDENTIALS, unknown.status());
        assertNull(wrongPw.session());
        assertNull(unknown.session());
    }

    @Test
    @DisplayName("连续失败达到阈值后锁定，并给出剩余秒数")
    void locksAfterRepeatedFailures() {
        AuthService auth = new AuthService(baseConfig()
                .users(List.of(new HarkenWebConfig.UserSpec("harlan", fastHash("pw"), "ADMIN")))
                .build());

        auth.login("harlan", "x");
        auth.login("harlan", "x");
        AuthService.LoginResult third = auth.login("harlan", "x");

        assertEquals(AuthService.LoginStatus.LOCKED, third.status());
        assertTrue(third.retryAfterSeconds() > 0);

        // 锁定期间即使密码正确也进不去
        assertEquals(AuthService.LoginStatus.LOCKED, auth.login("harlan", "pw").status());
    }

    @Test
    @DisplayName("登录成功会清零失败计数，不会被历史错误拖累")
    void successResetsFailures() {
        AuthService auth = new AuthService(baseConfig()
                .users(List.of(new HarkenWebConfig.UserSpec("harlan", fastHash("pw"), "ADMIN")))
                .build());

        auth.login("harlan", "x");
        auth.login("harlan", "x");
        assertTrue(auth.login("harlan", "pw").ok());

        // 再来两次错误也不该锁（阈值 3）
        auth.login("harlan", "x");
        auth.login("harlan", "x");
        assertTrue(auth.login("harlan", "pw").ok());
    }

    @Test
    @DisplayName("重载凭据会踢掉所有会话并报告结果")
    void reloadDropsSessions() {
        AuthService auth = new AuthService(baseConfig()
                .users(List.of(new HarkenWebConfig.UserSpec("harlan", fastHash("pw"), "ADMIN")))
                .build());
        String token = auth.login("harlan", "pw").session().token();
        assertEquals(1, auth.activeSessionCount());

        AuthService.ReloadResult result = auth.reload();

        assertEquals(1, result.userCount());
        assertEquals(1, result.droppedSessions());
        assertEquals(0, auth.activeSessionCount());
        assertNull(auth.touch(token), "重载后旧会话必须失效");
    }

    @Test
    @DisplayName("环境变量引用不存在时，该账号被禁用而不是放行")
    void missingEnvDisablesAccount() {
        AuthService auth = new AuthService(baseConfig()
                .users(List.of(new HarkenWebConfig.UserSpec("ghost",
                        "env:HARKEN_DEFINITELY_MISSING_VAR", "ADMIN")))
                .build());

        assertEquals(0, auth.userCount());
        assertEquals(AuthService.LoginStatus.NO_ACCOUNTS, auth.login("ghost", "anything").status());
    }

    @Test
    @DisplayName("未配置账号时生成随机密码账号（userCount=1），而不是放行空认证")
    void generatesPasswordWhenNoUsers() {
        AuthService auth = new AuthService(HarkenWebConfig.builder()
                .generatePasswordIfNoUsers(true)
                .build());

        assertEquals(1, auth.userCount());
        // 密码是随机的，我们只知道「空密码肯定不对」
        assertFalse(auth.login("admin", "").ok());
    }

    @Test
    @DisplayName("未配置账号且禁止生成时，登录返回 NO_ACCOUNTS")
    void noAccountsWhenGenerationDisabled() {
        AuthService auth = new AuthService(baseConfig().build());

        assertEquals(0, auth.userCount());
        assertEquals(AuthService.LoginStatus.NO_ACCOUNTS, auth.login("admin", "x").status());
    }

    @Test
    @DisplayName("AI Token：未配置时通道关闭，任何令牌都不通过")
    void aiDisabledWithoutToken() {
        AuthService auth = new AuthService(baseConfig().build());

        assertFalse(auth.aiEnabled());
        assertFalse(auth.checkAiToken(""));
        assertFalse(auth.checkAiToken("anything"));
    }

    @Test
    @DisplayName("AI Token：配置后按常量时间比较，错误令牌不通过")
    void aiTokenCheck() {
        AuthService auth = new AuthService(baseConfig().aiToken("s3cr3t-token").build());

        assertTrue(auth.aiEnabled());
        assertTrue(auth.checkAiToken("s3cr3t-token"));
        assertFalse(auth.checkAiToken("s3cr3t-toke"));
        assertFalse(auth.checkAiToken(""));
        assertFalse(auth.checkAiToken(null));
    }

    @Test
    @DisplayName("AI Token 支持 env: 引用")
    void aiTokenFromEnv() {
        // 用一个一定存在的环境变量：PATH 不会为空
        AuthService auth = new AuthService(baseConfig().aiToken("env:PATH").build());

        assertTrue(auth.aiEnabled());
        assertTrue(auth.checkAiToken(System.getenv("PATH")), "env:PATH 应解析成 PATH 的实际值");
    }
}
