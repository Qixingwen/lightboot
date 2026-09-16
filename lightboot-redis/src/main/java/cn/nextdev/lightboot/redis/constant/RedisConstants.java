package cn.nextdev.lightboot.redis.constant;

/**
 * Redis 模块通用常量。
 *
 * <p>集中管理序列号格式等常量，供 {@code SequenceService} 等组件统一引用。
 */
public final class RedisConstants {

    /**
     * 私有构造方法，防止实例化。
     */
    private RedisConstants() {
    }

    /**
     * 全局序列号 Key 命名空间（不含项目前缀）。
     * 完整 Key 格式为 {@code {keyPrefix}{SEQUENCE_KEY_PREFIX}{业务前缀}:{日期}}
     * （如 {@code light-boot:global:sequence:ORD:20260909}）。
     */
    public static final String SEQUENCE_KEY_PREFIX = "global:sequence:";

    /**
     * 序列号日期部分的格式模式。
     */
    public static final String DATE_PATTERN = "yyyyMMdd";

    /**
     * 序列号初始位数（不足时左补零）。
     */
    public static final int INITIAL_SEQUENCE_LENGTH = 4;

    /**
     * 序列号使用初始位数时的最大值（9999），超过后位数自动扩展。
     */
    public static final long MAX_INITIAL_SEQUENCE = 9999L;

}
