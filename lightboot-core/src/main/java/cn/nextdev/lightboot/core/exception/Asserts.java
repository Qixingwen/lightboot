package cn.nextdev.lightboot.core.exception;

import cn.nextdev.lightboot.core.api.IErrorCode;

/**
 * 提供便捷方式抛出 {@link ApiException} 的工具类。
 *
 * <p>典型用法是在服务层校验或前置条件检查中：
 * <pre>{@code
 *   if (user == null) {
 *       Asserts.fail(ResultCode.UNAUTHORIZED);
 *   }
 * }</pre>
 *
 * @see ApiException
 * @see IErrorCode
 */
public class Asserts {

    /**
     * 工具类，禁止实例化。
     */
    private Asserts() {
    }

    /**
     * 抛出携带自定义消息的 {@link ApiException}。
     *
     * @param message 错误消息
     * @throws ApiException 始终抛出
     */
    public static void fail(String message) {
        throw new ApiException(message);
    }

    /**
     * 抛出包装指定 {@link IErrorCode} 的 {@link ApiException}。
     *
     * @param errorCode 异常中携带的错误码
     * @throws ApiException 始终抛出
     */
    public static void fail(IErrorCode errorCode) {
        throw new ApiException(errorCode);
    }

    /**
     * 断言表达式为 {@code true}，否则抛出携带错误码的异常。
     *
     * @param expression 待断言的表达式
     * @param errorCode  条件不满足时使用的错误码
     * @throws ApiException 当表达式为 {@code false} 时抛出
     */
    public static void isTrue(boolean expression, IErrorCode errorCode) {
        if (!expression) {
            fail(errorCode);
        }
    }

    /**
     * 断言表达式为 {@code true}，否则抛出携带自定义消息的异常。
     *
     * @param expression 待断言的表达式
     * @param message    条件不满足时的错误消息
     * @throws ApiException 当表达式为 {@code false} 时抛出
     */
    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            fail(message);
        }
    }

    /**
     * 断言对象不为 {@code null}，否则抛出携带错误码的异常。
     *
     * @param object    待检查的对象
     * @param errorCode 对象为 {@code null} 时使用的错误码
     * @throws ApiException 当对象为 {@code null} 时抛出
     */
    public static void notNull(Object object, IErrorCode errorCode) {
        if (object == null) {
            fail(errorCode);
        }
    }

    /**
     * 断言对象不为 {@code null}，否则抛出携带自定义消息的异常。
     *
     * @param object  待检查的对象
     * @param message 对象为 {@code null} 时的错误消息
     * @throws ApiException 当对象为 {@code null} 时抛出
     */
    public static void notNull(Object object, String message) {
        if (object == null) {
            fail(message);
        }
    }

    /**
     * 断言表达式为 {@code false}，否则抛出携带错误码的异常。
     *
     * @param expression 待断言的表达式
     * @param errorCode  条件为 {@code true} 时使用的错误码
     * @throws ApiException 当表达式为 {@code true} 时抛出
     */
    public static void isFalse(boolean expression, IErrorCode errorCode) {
        if (expression) {
            fail(errorCode);
        }
    }

}
