package cn.nextdev.lightboot.core.api;

/**
 * 框架统一错误码接口。
 *
 * <p>实现类需定义数字类型的 {@code code} 和可读的 {@code message}，
 * 为不同模块或传输层（REST、gRPC、消息队列等）提供统一的错误状态描述方式。
 *
 * @see ResultCode
 */
public interface IErrorCode {

    /**
     * 获取错误码。
     *
     * @return 错误码数值
     */
    long getCode();

    /**
     * 获取错误描述信息。
     *
     * @return 与此错误码关联的默认描述信息
     */
    String getMessage();

}
