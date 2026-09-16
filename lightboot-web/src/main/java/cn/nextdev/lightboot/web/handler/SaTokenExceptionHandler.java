package cn.nextdev.lightboot.web.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.nextdev.lightboot.core.api.Result;
import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SaToken 认证与授权异常处理器。
 *
 * <p>仅当 {@code sa-token-core} 存在于类路径时才生效。
 * 处理 SaToken 的登录认证、权限、角色及上下文异常。
 * HTTP 状态码由 {@link HttpStatusCodeResolver} 按 {@code Result.code} 映射（默认开启，可退回全 200）。
 */
@RestControllerAdvice
@ConditionalOnClass(name = "cn.dev33.satoken.exception.SaTokenException")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class SaTokenExceptionHandler {

    private final HttpStatusCodeResolver statusCodeResolver;

    /**
     * 创建 SaToken 异常处理器。
     *
     * @param statusCodeResolver HTTP 状态码解析器
     */
    public SaTokenExceptionHandler(HttpStatusCodeResolver statusCodeResolver) {
        this.statusCodeResolver = statusCodeResolver;
    }

    /**
     * 处理认证失败异常（未登录或令牌无效）。
     *
     * @param e 未登录异常
     * @return 未授权（401）响应
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<?>> handleNotLoginException(NotLoginException e) {
        log.warn("Authentication failed: {}", e.getMessage());
        long code = ResultCode.UNAUTHORIZED.getCode();
        Result<?> body = Result.unauthorized();
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理授权失败异常（权限不足或角色不符）。
     *
     * @param e SaToken 权限/角色异常
     * @return 禁止访问（403）响应
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<Result<?>> handleSaTokenAuthorizationException(SaTokenException e) {
        log.warn("Authorization failed: {}", e.getMessage());
        long code = ResultCode.FORBIDDEN.getCode();
        Result<?> body = Result.forbidden();
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理 SaToken 上下文异常（框架集成配置错误）。
     *
     * @param e SaToken 上下文异常
     * @return 统一错误响应
     */
    @ExceptionHandler(SaTokenContextException.class)
    public ResponseEntity<Result<?>> handleSaTokenContextException(SaTokenContextException e) {
        log.error("SaToken context error: {}", e.getMessage(), e);
        long code = -1L;
        Result<?> body = Result.failed(ResultCode.FAILED, "认证上下文异常，请检查系统配置");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 兜底处理 SaToken 通用异常。
     *
     * @param e SaToken 异常
     * @return 未授权（401）响应
     */
    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<Result<?>> handleSaTokenException(SaTokenException e) {
        log.warn("SaToken exception: {}", e.getMessage());
        long code = ResultCode.UNAUTHORIZED.getCode();
        Result<?> body = Result.unauthorized();
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

}
