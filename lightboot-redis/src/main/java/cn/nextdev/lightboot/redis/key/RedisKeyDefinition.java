package cn.nextdev.lightboot.redis.key;

/**
 * Redis Key 定义接口。
 *
 * <p>框架层只定义规范，不定义具体业务 Key。每个业务模块通过枚举实现此接口，自由定义自己的 Redis Key。
 *
 * <p>使用示例（在业务模块中）：
 * <pre>{@code
 * public enum RedisKeys implements RedisKeyDefinition {
 *     AUTH_SMS_CODE("auth:sms:code:", 10L),
 *     ;
 *     private final String prefix;
 *     private final Long timeout;
 *     RedisKeys(String prefix, Long timeout) { ... }
 *     // getPrefix(), getTimeout() 由枚举提供
 *     // buildKey() 由接口 default 方法提供
 * }
 * }</pre>
 *
 * @see cn.nextdev.lightboot.redis.service.RedisService
 */
public interface RedisKeyDefinition {

    /**
     * Key 前缀，例如 {@code "auth:sms:code:"}。
     *
     * @return Key 前缀
     */
    String getPrefix();

    /**
     * 超时时间，单位为分钟。
     *
     * @return 超时时间（分钟）
     */
    Long getTimeout();

    /**
     * 构建完整的 Redis Key。
     *
     * <p>默认实现：{@code prefix + suffix}，
     * 例如 {@code "auth:sms:code:" + "13800138000"} → {@code "auth:sms:code:13800138000"}。
     *
     * @param suffix Key 后缀
     * @return 完整的 Redis Key
     */
    default String buildKey(String suffix) {
        return getPrefix() + suffix;
    }

}
