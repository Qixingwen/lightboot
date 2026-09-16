package cn.nextdev.lightboot.web.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.web.config.WebProperties;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SaTokenExceptionHandler 切片测试：验证 4 个 @ExceptionHandler 分支均转换为统一 Result，
 * 并携带真实 HTTP 状态码（40100 → 401、40300 → 403、-1 → 500）。
 * 使用 MockMvc standalone 模式，显式挂载测试 controller 与 SaTokenExceptionHandler，
 * 不启动完整 Spring 上下文（sa-token-core 为本模块 optional 依赖，测试类路径可见）。
 */
class SaTokenExceptionHandlerTest {

    private MockMvc mockMvc;

    /**
     * 测试用 controller，按请求路径抛出不同 SaToken 异常。
     */
    @RestController
    static class TestController {
        @GetMapping("/api/sa/not-login")
        public void throwNotLogin() {
            // 构造：message = 未提供 token 的默认文案，loginType = "login"，type = NOT_TOKEN
            throw new NotLoginException(NotLoginException.NOT_TOKEN_MESSAGE, "login", NotLoginException.NOT_TOKEN);
        }

        @GetMapping("/api/sa/not-login-timeout")
        public void throwNotLoginTokenTimeout() {
            // type = TOKEN_TIMEOUT 时 sa-token 原生文案不同，但 handler 不按 type 分支，响应应保持不变
            throw new NotLoginException(NotLoginException.TOKEN_TIMEOUT_MESSAGE, "login", NotLoginException.TOKEN_TIMEOUT);
        }

        @GetMapping("/api/sa/not-permission")
        public void throwNotPermission() {
            throw new NotPermissionException("user:delete");
        }

        @GetMapping("/api/sa/not-role")
        public void throwNotRole() {
            throw new NotRoleException("admin");
        }

        @GetMapping("/api/sa/context")
        public void throwSaTokenContext() {
            throw new SaTokenContextException("当前线程缺少 SaToken 上下文");
        }

        @GetMapping("/api/sa/generic")
        public void throwGenericSaToken() {
            throw new SaTokenException("通用 SaToken 异常");
        }
    }

    @BeforeEach
    void setUp() {
        WebProperties props = new WebProperties();
        HttpStatusCodeResolver resolver = new HttpStatusCodeResolver(props);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new SaTokenExceptionHandler(resolver))
                .build();
    }

    /**
     * NotLoginException 映射为 UNAUTHORIZED(40100) 与 HTTP 401，message 固定文案（不透出 sa-token 原生文案）。
     */
    @Test
    void notLoginMapsTo401WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/sa/not-login"))
                .andExpect(status().isUnauthorized()) // UNAUTHORIZED 40100 → HTTP 401
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.message").value("登录会话失效，请重新登录"));
    }

    /**
     * NotLoginException 的 type 不影响响应：TOKEN_TIMEOUT 与 NOT_TOKEN 返回完全相同的 code/message，
     * 锁定「handler 不按 sa-token type 分支」的契约。
     */
    @Test
    void notLoginResponseTypeIsIndependentOfType() throws Exception {
        mockMvc.perform(get("/api/sa/not-login-timeout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.message").value("登录会话失效，请重新登录"));
    }

    /**
     * NotPermissionException 映射为 FORBIDDEN(40300) 与 HTTP 403。
     */
    @Test
    void notPermissionMapsTo403() throws Exception {
        mockMvc.perform(get("/api/sa/not-permission"))
                .andExpect(status().isForbidden()) // FORBIDDEN 40300 → HTTP 403
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()))
                .andExpect(jsonPath("$.message").value("没有相关权限"));
    }

    /**
     * NotRoleException 与 NotPermissionException 共用同一 handler，同样映射为 FORBIDDEN(40300) 与 HTTP 403。
     */
    @Test
    void notRoleMapsTo403() throws Exception {
        mockMvc.perform(get("/api/sa/not-role"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()))
                .andExpect(jsonPath("$.message").value("没有相关权限"));
    }

    /**
     * SaTokenContextException（框架集成配置错误）映射为 FAILED(-1)、固定中文文案与 HTTP 500。
     */
    @Test
    void saTokenContextMapsTo500WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/sa/context"))
                .andExpect(status().isInternalServerError()) // -1 → HTTP 500
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("认证上下文异常，请检查系统配置"));
    }

    /**
     * 兜底分支：未被前三个 handler 命中的 SaTokenException 映射为 UNAUTHORIZED(40100) 与 HTTP 401。
     */
    @Test
    void genericSaTokenExceptionFallsBackTo401() throws Exception {
        mockMvc.perform(get("/api/sa/generic"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.message").value("登录会话失效，请重新登录"));
    }

}
