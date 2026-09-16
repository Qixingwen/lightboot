package cn.nextdev.lightboot.redis.serializer;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * 序列号脚本返回值（Redis 整数回复）的反序列化器。
 *
 * <p>专门用于 {@code SequenceService} 的 Lua 脚本 result（{@code resultSerializer} 位置），
 * 将 Redis {@code INCR} 返回的整数回复字节解析为 {@link Long}。与值序列化器（JSON）解耦，
 * 避免脚本返回值被错误地按 JSON 处理。
 *
 * <p>ARGV 的纯文本化由 {@code SequenceService} 在脚本 args 上显式指定的
 * {@link RedisSerializer#string()} 保证（避免被 JSON 加引号导致 {@code PEXPIREAT}
 * 报「value is not an integer」），本类不参与 args 序列化。
 */
public class SequenceLongSerializer implements RedisSerializer<Long> {

    /**
     * 无状态单例实例。{@link RedisSerializer} 的序列化/反序列化不持有可变状态，可安全共享。
     */
    public static final SequenceLongSerializer INSTANCE = new SequenceLongSerializer();

    /**
     * 默认构造方法。该反序列化器无状态，推荐直接使用 {@link #INSTANCE} 而非重复构造。
     */
    public SequenceLongSerializer() {
    }

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
