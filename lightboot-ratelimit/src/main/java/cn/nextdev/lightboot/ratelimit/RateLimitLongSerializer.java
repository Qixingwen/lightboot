package cn.nextdev.lightboot.ratelimit;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * 限流脚本返回值（整数回复）的反序列化器。
 *
 * <p>专门用于 {@link RateLimitRedisService} 的 Lua 脚本 result，将 Redis 整数回复的字节
 * 解析为 {@link Long}。与值序列化器（JSON）解耦，避免脚本返回值被错误地按 JSON 处理。
 */
class RateLimitLongSerializer implements RedisSerializer<Long> {

    static final RateLimitLongSerializer INSTANCE = new RateLimitLongSerializer();

    @Override
    public byte[] serialize(Long t) throws SerializationException {
        if (t == null) {
            return null;
        }
        return String.valueOf(t).getBytes();
    }

    @Override
    public Long deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null) {
            return null;
        }
        return Long.parseLong(new String(bytes).trim());
    }
}
