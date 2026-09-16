package cn.nextdev.lightboot.web.handler;

import cn.nextdev.lightboot.core.api.Result;
import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 数据库访问异常处理器，拦截并统一处理数据库相关的异常。
 *
 * <p>仅当类路径中存在 {@code spring-tx}（包含 Spring DAO 异常体系）时才生效。
 * 处理的异常类型包括：唯一约束冲突、数据完整性违反以及通用数据访问异常。
 * HTTP 状态码由 {@link HttpStatusCodeResolver} 按 {@code Result.code} 映射（默认开启，可退回全 200）。
 */
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class DatabaseExceptionHandler {

    private final HttpStatusCodeResolver statusCodeResolver;

    /**
     * 创建数据库异常处理器。
     *
     * @param statusCodeResolver HTTP 状态码解析器
     */
    public DatabaseExceptionHandler(HttpStatusCodeResolver statusCodeResolver) {
        this.statusCodeResolver = statusCodeResolver;
    }

    /**
     * 处理唯一约束冲突异常（重复键插入）。
     *
     * <p>客户端试图创建已存在的资源 → 映射为 HTTP 409 Conflict，业务码 {@link ResultCode#CONFLICT}(40900)。
     *
     * @param e 重复键异常
     * @return 统一错误响应（409）
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<?>> handleDuplicateKeyException(DuplicateKeyException e) {
        log.warn("Duplicate key violation: {}", e.getMessage());
        Result<?> body = Result.failed(ResultCode.CONFLICT, "数据已存在，唯一约束冲突");
        return ResponseEntity.status(statusCodeResolver.resolve(ResultCode.CONFLICT.getCode())).body(body);
    }

    /**
     * 处理数据完整性违反异常（外键约束、非空约束等）。
     *
     * <p>客户端提交了非法/不完整数据 → 映射为 HTTP 400 Bad Request，业务码 {@link ResultCode#BAD_REQUEST}(40000)。
     *
     * @param e 数据完整性违反异常
     * @return 统一错误响应（400）
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<?>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMessage());
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, "数据完整性约束违反，操作失败");
        return ResponseEntity.status(statusCodeResolver.resolve(ResultCode.BAD_REQUEST.getCode())).body(body);
    }

    /**
     * 兜底处理通用数据访问异常。
     *
     * <p>真正的服务端数据库错误 → 仍映射为 HTTP 500，业务码 -1。
     *
     * @param e 数据访问异常
     * @return 统一错误响应（500）
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<?>> handleDataAccessException(DataAccessException e) {
        log.error("Data access error: {}", e.getMessage(), e);
        Result<?> body = Result.failed("数据库操作异常");
        return ResponseEntity.status(statusCodeResolver.resolve(-1L)).body(body);
    }

}
