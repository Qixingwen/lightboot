package cn.nextdev.lightboot.core.api;

/**
 * 框架内置的 {@link IErrorCode} 枚举，涵盖最常见的 API 响应状态。
 *
 * <p>这些常量作为所有模块的标准错误码集合使用。
 * 领域特定的错误码应在各自的枚举中实现 {@link IErrorCode} 接口进行定义。
 *
 * @see IErrorCode
 * @see Result
 */
public enum ResultCode implements IErrorCode {

    /**
     * 操作成功
     */
    SUCCESS(0, "操作成功"),

    /**
     * 操作失败
     */
    FAILED(-1, "操作失败"),

    /**
     * 认证失败（账号或密码错误）
     */
    AUTH_FAILED(10001, "账号或密码错误"),

    /**
     * 请求参数校验失败
     */
    BAD_REQUEST(40000, "请求参数错误或格式无效"),

    /**
     * 登录会话已失效，需重新认证
     */
    UNAUTHORIZED(40100, "登录会话失效，请重新登录"),

    /**
     * 权限不足，禁止访问
     */
    FORBIDDEN(40300, "没有相关权限"),

    /**
     * 请求的资源不存在
     */
    NOT_FOUND(40400, "请求的资源不存在"),

    /**
     * 数据冲突，资源已存在（唯一约束冲突）
     */
    CONFLICT(40900, "数据冲突，资源已存在"),

    /**
     * 上传文件大小超过限制
     */
    FILE_TOO_LARGE(41300, "文件大小超过限制"),

    /**
     * 系统内部错误
     */
    INTERNAL_ERROR(50000, "系统内部错误");

    private final long code;

    private final String message;

    ResultCode(long code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public long getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

}
