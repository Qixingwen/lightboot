package cn.nextdev.lightboot.web.handler;

import cn.nextdev.lightboot.core.api.Result;
import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.core.exception.ApiException;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.Assert;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import javax.crypto.BadPaddingException;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器，提供生产级异常拦截与统一响应。
 *
 * <p>核心特性：
 * <ul>
 *   <li>所有异常统一返回 {@code ResponseEntity<Result<?>>} 响应格式</li>
 *   <li>HTTP 状态码由 {@link HttpStatusCodeResolver} 按 {@code Result.code} 映射（默认开启，可退回全 200）</li>
 *   <li>覆盖常见的 Web 层、参数校验、IO 及运行时异常</li>
 *   <li>结构化日志记录，便于问题排查</li>
 *   <li>面向安全的错误信息，避免泄露内部实现细节</li>
 *   <li>兜底异常处理，确保无遗漏</li>
 * </ul>
 */
@RestControllerAdvice
@Order()
@Slf4j
public class GlobalExceptionHandler {

    private final HttpStatusCodeResolver statusCodeResolver;

    /**
     * 创建全局异常处理器。
     *
     * @param statusCodeResolver HTTP 状态码解析器
     */
    public GlobalExceptionHandler(HttpStatusCodeResolver statusCodeResolver) {
        this.statusCodeResolver = statusCodeResolver;
    }

    /**
     * 处理自定义 API 业务异常。
     *
     * @param e API 业务异常
     * @return 携带错误码和消息的统一错误响应
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<?>> handleApiException(ApiException e) {
        logApiException(e);
        long code = e.getErrorCode() != null ? e.getErrorCode().getCode() : -1L;
        Result<?> body = e.getErrorCode() != null
                ? Result.failed(e.getErrorCode(), e.getMessage())
                : Result.failed(e.getMessage());
        // 当异常携带重试等待秒数（如限流 429）时，写入 Retry-After 头告知客户端等待时长。
        // 通过基类钩子 ApiException#getRetryAfterSeconds 解耦，web 模块无需依赖具体业务异常类型。
        long retryAfterSeconds = e.getRetryAfterSeconds();
        if (retryAfterSeconds > 0) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            return ResponseEntity.status(statusCodeResolver.resolve(code)).headers(headers).body(body);
        }
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理请求参数校验失败异常（{@code @Valid} 校验及表单绑定）。
     *
     * @param e 包含字段错误信息的绑定异常
     * @return 携带校验错误详情的统一错误响应
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result<?>> handleValidationException(BindException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.toList());

        String message = errors.isEmpty() ? "参数验证失败" : String.join("; ", errors);
        log.warn("Parameter validation failed: {}", message);

        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, message);
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理方法参数类型不匹配异常。
     *
     * @param e 类型不匹配异常
     * @return 包含参数名与期望类型信息的统一错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String message = String.format("参数类型错误: '%s' 应为 %s 类型",
                e.getName(),
                e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "未知");

        log.warn("Method argument type mismatch: {}", message);
        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, message);
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理缺少必需请求头异常。
     *
     * @param e 缺少请求头异常
     * @return 包含缺失请求头名的统一错误响应
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<?>> handleMissingRequestHeaderException(MissingRequestHeaderException e) {
        String message = String.format("缺少必需请求头: %s", e.getHeaderName());
        log.warn("Missing request header: {}", message);
        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, message);
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理缺少必需请求参数异常。
     *
     * @param e 缺少参数异常
     * @return 包含缺失参数名的统一错误响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        String message = String.format("缺少必需参数: %s", e.getParameterName());
        log.warn("Missing request parameter: {}", message);
        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, message);
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理请求体不可读异常（JSON 格式错误或数据类型不匹配）。
     *
     * @param e 消息不可读异常
     * @return 统一错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("HTTP message not readable: {}", e.getMessage());
        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, "请求体格式错误或数据类型不匹配");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理不支持的 HTTP 请求方法异常。
     *
     * @param e 请求方法不支持异常
     * @return 包含当前方法与支持方法列表的统一错误响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        String supportedMethods = e.getSupportedMethods() != null
                ? String.join(", ", e.getSupportedMethods())
                : "无";

        String message = String.format("不支持的请求方法: %s，支持的方法: %s",
                e.getMethod(),
                supportedMethods);

        log.warn("HTTP method not supported: {}", message);
        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, message);
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理不支持的媒体类型异常。
     *
     * @param e 媒体类型不支持异常
     * @return 统一错误响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException e) {
        String message = String.format("不支持的媒体类型: %s", e.getContentType());
        log.warn("Media type not supported: {}", message);
        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, message);
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理文件上传大小超限异常。
     *
     * @param e 上传大小超限异常
     * @return 统一错误响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("File upload size exceeded: {}", e.getMessage());
        long code = ResultCode.FILE_TOO_LARGE.getCode();
        Result<?> body = Result.failed(ResultCode.FILE_TOO_LARGE, ResultCode.FILE_TOO_LARGE.getMessage());
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理 IO 异常。
     *
     * @param e IO 异常
     * @return 统一错误响应
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<?>> handleIOException(IOException e) {
        log.error("IO exception: {}", e.getMessage(), e);
        long code = -1L;
        Result<?> body = Result.failed("文件读写异常");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理数据解密填充异常（密码学相关）。
     *
     * @param e 填充异常
     * @return 统一错误响应
     */
    @ExceptionHandler(BadPaddingException.class)
    public ResponseEntity<Result<?>> handleBadPaddingException(BadPaddingException e) {
        log.error("Bad padding exception: {}", e.getMessage());
        long code = -1L;
        Result<?> body = Result.failed("数据解密异常");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理 404 资源不存在异常。
     *
     * @param e 处理器未找到异常
     * @return 统一错误响应
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<?>> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.warn("No handler found: {} {}", e.getHttpMethod(), e.getRequestURL());
        long code = ResultCode.NOT_FOUND.getCode();
        Result<?> body = Result.failed(ResultCode.NOT_FOUND, "请求的资源不存在");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理静态资源未找到异常。
     *
     * @param e 资源未找到异常
     * @return 统一错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("No resource found: {} {}", e.getHttpMethod(), e.getResourcePath());
        long code = ResultCode.NOT_FOUND.getCode();
        Result<?> body = Result.failed(ResultCode.NOT_FOUND, "请求的资源不存在");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理非法参数异常。
     *
     * @param e 非法参数异常
     * @return 统一错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        long code = ResultCode.BAD_REQUEST.getCode();
        Result<?> body = Result.failed(ResultCode.BAD_REQUEST, "请求参数不合法");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理非法状态异常。
     *
     * @param e 非法状态异常
     * @return 统一错误响应
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<?>> handleIllegalStateException(IllegalStateException e) {
        log.error("Illegal state: {}", e.getMessage(), e);
        long code = -1L;
        Result<?> body = Result.failed("系统状态异常");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 处理空指针异常。
     *
     * @param e 空指针异常
     * @return 统一错误响应
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Result<?>> handleNullPointerException(NullPointerException e) {
        log.error("Null pointer exception: {}", e.getMessage(), e);
        long code = ResultCode.INTERNAL_ERROR.getCode();
        Result<?> body = Result.failed(ResultCode.INTERNAL_ERROR, "系统内部错误");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 兜底处理运行时异常。
     *
     * @param e 运行时异常
     * @return 统一错误响应
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<?>> handleRuntimeException(RuntimeException e) {
        log.error("Unhandled runtime exception: [{}]: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        long code = -1L;
        Result<?> body = Result.failed("运行时处理异常");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 兜底处理所有未捕获的异常。
     *
     * @param e 顶层异常
     * @return 统一错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleException(Exception e) {
        log.error("Unhandled exception: {}", e.getMessage(), e);
        long code = -1L;
        Result<?> body = Result.failed("系统异常，请稍后重试");
        return ResponseEntity.status(statusCodeResolver.resolve(code)).body(body);
    }

    /**
     * 格式化字段错误信息。
     *
     * @param error 字段错误
     * @return 格式化后的错误描述，如 {@code "fieldName: 错误消息"}
     */
    private String formatFieldError(FieldError error) {
        Assert.notNull(error, "FieldError must not be null");
        return String.format("%s: %s", error.getField(), error.getDefaultMessage());
    }

    /**
     * 记录 API 异常。统一使用 WARN 级别，按 errorCode 是否存在调整消息格式。
     *
     * @param e API 业务异常
     */
    private void logApiException(ApiException e) {
        if (e.getErrorCode() != null) {
            log.warn("API exception [code: {}, message: {}]", e.getErrorCode().getCode(), e.getMessage());
        } else {
            log.warn("API exception: {}", e.getMessage());
        }
    }

}
