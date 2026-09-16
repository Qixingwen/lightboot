package cn.nextdev.lightboot.redis.serializer;

import cn.nextdev.lightboot.redis.config.RedisProperties;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.util.List;
import java.util.Locale;

/**
 * Redis JSON 序列化器工厂（Jackson 3）。
 *
 * <p>创建框架统一的 {@link GenericJacksonJsonRedisSerializer}，供
 * {@link cn.nextdev.lightboot.redis.config.RedisTemplateAutoConfiguration} 和
 * {@link cn.nextdev.lightboot.redis.config.RedisCacheAutoConfiguration} 共享，
 * 确保 RedisTemplate 与 CacheManager 使用完全一致的序列化策略。
 *
 * <p>默认使用白名单类型校验器，仅允许反序列化已知的安全包路径。可通过
 * {@code light-boot.redis.serializer.base-packages} 扩展白名单范围。
 *
 * @see RedisProperties
 */
public class RedisJsonSerializerFactory {

    /**
     * 禁止通过 base-packages 放行的危险包前缀（含 JDK 内部与常见反序列化 gadget 所在包）。
     */
    private static final List<String> FORBIDDEN_PREFIXES =
            List.of("java", "javax", "com.sun", "sun", "jdk", "org.xml", "org.w3c");

    /**
     * 默认构造方法。
     */
    public RedisJsonSerializerFactory() {
    }

    /**
     * 创建带类型保留的 JSON 序列化器。
     *
     * <p>使用白名单校验器限制可反序列化的类型，确保写入 Redis 的 JSON 数据可以安全地
     * 反序列化为正确的 Java 类型，同时防止反序列化漏洞。
     *
     * @param properties Redis 扩展配置属性，提供序列化器白名单等配置
     * @return 配置完成的 {@link GenericJacksonJsonRedisSerializer}
     */
    public GenericJacksonJsonRedisSerializer create(RedisProperties properties) {
        JsonMapper jsonMapper = JsonMapper.builder()
                .changeDefaultVisibility(visibilityChecker ->
                        visibilityChecker.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY))
                .activateDefaultTyping(
                        buildTypeValidator(properties),
                        DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                )
                .build();
        return new GenericJacksonJsonRedisSerializer(jsonMapper);
    }

    /**
     * 构建多态类型校验器。
     *
     * <p>默认允许以下安全包路径：
     * <ul>
     *   <li>显式 JDK 具体类型白名单（String/Number/Boolean/Collection/Map 等）</li>
     *   <li>{@code cn.nextdev.lightboot.} — 框架内部类型</li>
     * </ul>
     *
     * <p>用户可通过 {@code light-boot.redis.serializer.base-packages} 添加业务包路径。
     *
     * @param properties Redis 配置属性
     * @return 类型校验器
     */
    private PolymorphicTypeValidator buildTypeValidator(RedisProperties properties) {
        BasicPolymorphicTypeValidator.Builder builder = BasicPolymorphicTypeValidator.builder()
                // 仅显式允许缓存常用 JDK 具体类型
                .allowIfSubType(String.class)
                .allowIfSubType(Number.class) // Long / Integer / Double / BigDecimal ...
                .allowIfSubType(Boolean.class)
                .allowIfSubType(Character.class)
                .allowIfSubType(java.util.Collection.class) // ArrayList / LinkedList ...
                .allowIfSubType(java.util.Map.class) // HashMap / LinkedHashMap ...
                .allowIfSubType("cn.nextdev.lightboot.");

        List<String> basePackages = validateBasePackages(properties.getSerializer() == null
                ? null
                : properties.getSerializer().getBasePackages());
        for (String basePackage : basePackages) {
            builder.allowIfSubType(basePackage);
        }

        return builder.build();
    }

    /**
     * 校验并归一化业务放行包：拒绝空串/通配符/危险前缀（fail-fast，防止一条配置使白名单形同虚设）；
     * 统一补尾点 {@code "."}，使 allowIfSubType 的前缀匹配具有包边界语义
     * （{@code com.example} 只匹配 {@code com.example.*}，不再放行 {@code com.exampleFoo}）。
     */
    private static List<String> validateBasePackages(List<String> basePackages) {
        if (basePackages == null || basePackages.isEmpty()) {
            return List.of();
        }
        return basePackages.stream().map(pkg -> {
            String trimmed = pkg == null ? "" : pkg.trim();
            if (trimmed.isEmpty() || trimmed.contains("*") || trimmed.contains("?") || trimmed.contains(" ")) {
                throw new IllegalStateException(
                        "light-boot.redis.serializer.base-packages 含非法配置项: '" + pkg + "'");
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            for (String danger : FORBIDDEN_PREFIXES) {
                if (lower.equals(danger) || lower.startsWith(danger + ".")) {
                    throw new IllegalStateException("禁止放行危险包前缀 '" + trimmed + "'（命中 '" + danger
                            + "'），这将使反序列化白名单形同虚设；如需缓存此类类型请使用业务 DTO 包裹");
                }
            }
            return trimmed.endsWith(".") ? trimmed : trimmed + ".";
        }).toList();
    }

}
