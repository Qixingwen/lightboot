package cn.nextdev.lightboot.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.slf4j.MDC;

/**
 * 框架统一 API 响应包装类。
 *
 * <p>所有 API 响应均由 {@code Result} 表示，包含以下四个字段：
 * <ul>
 *   <li>{@code code} — 数字状态码（0 表示成功，非 0 表示错误）</li>
 *   <li>{@code message}  — 可读的状态或错误描述信息</li>
 *   <li>{@code data} — 可选的载荷数据，类型为 {@code T}</li>
 *   <li>{@code traceId} — 请求追踪标识，构造时自动取自 MDC（无值时序列化省略）</li>
 * </ul>
 *
 * <p>提供了用于常见响应场景（成功、失败、参数错误、未授权、禁止访问）的静态工厂方法。
 * 该类为不可变类，所有字段在构造时设置。
 *
 * @param <T> 成功响应所携带的载荷类型
 * @see ResultCode
 * @see IErrorCode
 */
@EqualsAndHashCode
@Getter
@ToString
public class Result<T> implements java.io.Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * MDC 中 traceId 的 key，与 lightboot-logging 模块 {@code TraceContext.MDC_KEY} 对应。
     */
    private static final String MDC_TRACE_ID_KEY = "traceId";

    /**
     * 数字状态码，0 表示成功，非 0 表示错误
     */
    private final long code;

    /**
     * 状态或错误描述信息
     */
    private final String message;

    /**
     * 响应载荷数据
     */
    private final T data;

    /**
     * 当前请求的追踪标识。
     * 序列化时无值省略（{@code @JsonInclude(NON_NULL)}）。
     *
     * <p>构造时自动从 MDC 读取（key: {@code traceId}），无可用 traceId 时为 {@code null}。
     * 属传输上下文而非逻辑值，不参与 {@code equals}/{@code hashCode}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @EqualsAndHashCode.Exclude
    private final String traceId;

    /**
     * 私有构造方法，防止外部直接实例化；统一经静态工厂方法创建。
     *
     * @param code    业务状态码
     * @param message 提示消息
     * @param data    响应载荷数据
     */
    private Result(long code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = MDC.get(MDC_TRACE_ID_KEY);
    }

    /**
     * 创建无载荷的成功结果。
     *
     * @param <T> 推断的载荷类型
     * @return 成功结果
     */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 创建携带指定载荷的成功结果。
     *
     * @param data 响应载荷
     * @param <T>  载荷类型
     * @return 携带指定载荷的成功结果
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 创建携带自定义消息和载荷的成功结果。
     *
     * @param data    响应载荷
     * @param message 自定义成功消息
     * @param <T>     载荷类型
     * @return 携带指定消息和载荷的成功结果
     */
    public static <T> Result<T> success(T data, String message) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /**
     * 使用自定义 {@link IErrorCode} 创建成功结果。
     *
     * <p>允许下游项目指定自己的成功码和消息，例如 {@code SUCCESS(200, "操作成功")}。
     *
     * @param errorCode 提供状态码和消息的错误码定义
     * @param <T>       推断的载荷类型
     * @return 携带指定编码和消息的成功结果
     */
    public static <T> Result<T> success(IErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 使用自定义 {@link IErrorCode} 和载荷创建成功结果。
     *
     * @param errorCode 提供状态码和消息的错误码定义
     * @param data      响应载荷
     * @param <T>       载荷类型
     * @return 携带指定编码、消息和数据的成功结果
     */
    public static <T> Result<T> success(IErrorCode errorCode, T data) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), data);
    }

    /**
     * 创建携带自定义错误消息的失败结果。
     *
     * @param message 错误消息
     * @param <T>     推断的载荷类型
     * @return 携带指定错误消息的失败结果
     */
    public static <T> Result<T> failed(String message) {
        return new Result<>(ResultCode.FAILED.getCode(), message, null);
    }

    /**
     * 根据 {@link IErrorCode} 创建失败结果。
     *
     * @param errorCode 提供状态码和消息的错误码
     * @param <T>       推断的载荷类型
     * @return 携带错误码消息的失败结果
     */
    public static <T> Result<T> failed(IErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 根据 {@link IErrorCode} 创建失败结果，并使用自定义消息覆盖默认消息。
     *
     * @param errorCode 提供状态码的错误码
     * @param message   自定义错误消息，覆盖默认消息
     * @param <T>       推断的载荷类型
     * @return 携带自定义消息的失败结果
     */
    public static <T> Result<T> failed(IErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /**
     * 根据 {@link IErrorCode} 创建携带载荷的失败结果。
     *
     * @param errorCode 提供状态码和消息的错误码
     * @param data      响应载荷
     * @param <T>       载荷类型
     * @return 携带指定载荷的失败结果
     */
    public static <T> Result<T> failed(IErrorCode errorCode, T data) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), data);
    }

    /**
     * 创建参数错误（业务码 {@link ResultCode#BAD_REQUEST}，web 层映射为 HTTP 400 Bad Request）结果。
     *
     * @param message 参数校验或格式错误的描述信息
     * @param <T>     推断的载荷类型
     * @return 携带指定消息的参数错误结果
     */
    public static <T> Result<T> badRequest(String message) {
        return new Result<>(ResultCode.BAD_REQUEST.getCode(), message, null);
    }

    /**
     * 创建未授权（业务码 {@link ResultCode#UNAUTHORIZED}，web 层映射为 HTTP 401 Unauthorized）结果，使用默认消息。
     *
     * @param <T> 推断的载荷类型
     * @return 未授权结果
     */
    public static <T> Result<T> unauthorized() {
        return new Result<>(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage(), null);
    }

    /**
     * 创建禁止访问（业务码 {@link ResultCode#FORBIDDEN}，web 层映射为 HTTP 403 Forbidden）结果，使用默认消息。
     *
     * @param <T> 推断的载荷类型
     * @return 禁止访问结果
     */
    public static <T> Result<T> forbidden() {
        return new Result<>(ResultCode.FORBIDDEN.getCode(), ResultCode.FORBIDDEN.getMessage(), null);
    }

    /**
     * 是否为成功结果（code 等于 {@link ResultCode#SUCCESS} 的编码）。
     *
     * <p>注意：此方法严格检查 {@code code == 0}（即 {@link ResultCode#SUCCESS} 的 {@code getCode()}）。
     * 即使通过 {@link #success(IErrorCode)} 创建了带有非零 code 的 Result，只要 code 不为 0，
     * 此方法也会返回 {@code false}。这是设计上的严格定义，确保只有在标准成功码（0）时才被视为成功。
     *
     * @return 如果 code 等于 0 则返回 {@code true}，否则返回 {@code false}
     */
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }

    /**
     * 是否为失败结果。
     *
     * @return 如果 code 不等于 0 则返回 {@code true}，否则返回 {@code false}
     */
    public boolean failed() {
        return !isSuccess();
    }

}
