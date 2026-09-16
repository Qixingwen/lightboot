package cn.nextdev.lightboot.redis.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Redis 扩展配置属性（前缀：{@code light-boot.redis}）。
 *
 * <p>在 {@code spring.data.redis} 标准配置之外，提供框架级别的额外配置。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "light-boot.redis")
public class RedisProperties {

    /**
     * 默认构造方法。
     */
    public RedisProperties() {
    }

    /**
     * 是否启用 Redis 模块自动配置。
     */
    private boolean enabled = true;

    /**
     * Key 全局前缀，用于区分不同项目/环境（如 {@code "light-boot:"}）。
     * 应用于 {@link cn.nextdev.lightboot.redis.service.RedisService} 的全部业务 Key（{@code buildFullKey} 统一拼接）
     * 及 {@link cn.nextdev.lightboot.redis.service.SequenceService}、分布式锁等框架内部生成的 Key。
     * 缓存 Key 前缀由 {@code RedisCacheProperties.keyPrefix} 单独控制。
     */
    private String keyPrefix = "light-boot:";

    /**
     * 是否启用 RedisService 读操作容错降级。
     *
     * <p>开启后，{@link cn.nextdev.lightboot.redis.service.RedisService} 的读操作在 Redis 异常时
     * 返回 null/空集合（{@code hasKey}（含 Hash 版）与 {@code setIsMember} 返回 {@code false}）而不抛异常，
     * 避免缓存层故障影响主业务。默认 false（不改现有行为）。
     * 写操作不受影响，仍抛异常。
     */
    private boolean faultTolerant = false;

    /**
     * 命令超时（毫秒）。超过此时间的 Redis 命令抛异常，避免请求线程被无限挂起。默认 3000。
     *
     * <p>该值通过自定义 {@link org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer}
     * 应用到 Spring Boot 创建的 {@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory}，
     * 会覆盖 {@code spring.data.redis.timeout}。
     */
    private long commandTimeoutMillis = 3000;

    /**
     * 连接超时（毫秒）。默认 2000。
     *
     * <p>该值通过自定义 {@link org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer}
     * 应用到 Lettuce 的 {@link io.lettuce.core.ClientOptions}，经
     * {@link io.lettuce.core.SocketOptions#getConnectTimeout()} 生效，控制 TCP 建连阶段的超时。
     */
    private long connectTimeoutMillis = 2000;

    /**
     * 连接池配置。
     */
    private PoolProperties pool = new PoolProperties();

    /**
     * 序列化器配置。
     */
    private SerializerProperties serializer = new SerializerProperties();

    /**
     * 序列化器相关配置属性。
     */
    @Setter
    @Getter
    public static class SerializerProperties {

        /**
         * 默认构造方法。
         */
        public SerializerProperties() {
        }

        /**
         * 允许反序列化的业务包路径白名单。
         *
         * <p>框架默认已放行常用 JDK 具体类型（{@code String}、{@code Number}、{@code Boolean}、
         * {@code Character} 及 {@code Collection}/{@code Map} 的子类型）和 {@code cn.nextdev.lightboot.} 包；
         * {@code java.util.Date}、枚举等类型默认<b>不在</b>白名单内——如需缓存此类类型，请使用业务 DTO
         * 包裹而非放行 JDK 包。本配置仅接受业务包路径：配置项将做包点边界归一化（统一补尾点 {@code "."}），
         * 并拒绝空串/通配符与 {@code java}/{@code com.sun} 等危险前缀（启动即失败，fail-fast）。
         *
         * <p><b>注意</b>：本序列化器使用 {@code DefaultTyping.NON_FINAL}，只为非 final 类型写入
         * {@code @class} 类型标识。{@code java.time} 的类型均为 final，既不写类型标识、也不会查询白名单，
         * 把 {@code java.time} 加入本配置<b>无法</b>使其带类型往返（读回为字符串）。此类值建议存入
         * 自定义 DTO 中序列化，或为字段配置自定义（反）序列化器。
         *
         * <p>示例配置：
         * <pre>
         * light-boot:
         *   redis:
         *     serializer:
         *       base-packages:
         *         - com.example.entity
         *         - com.example.dto
         * </pre>
         */
        private List<String> basePackages;

    }

    /**
     * Lettuce 连接池配置（需 commons-pool2 在类路径上）。
     *
     * <p>注意：Spring Boot 创建 {@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory}
     * 时，仅当 {@code spring.data.redis.lettuce.pool.enabled=true}（或未显式设置且 commons-pool2 可用时）
     * 才会构建「带池」的客户端配置。本框架的自定义器在「带池」构建器出现时，将本配置覆盖到池上。
     * 因此若要使 {@code maxTotal/maxIdle/minIdle/maxWaitMillis} 生效，需同时启用 Boot 的连接池开关。
     */
    @Setter
    @Getter
    public static class PoolProperties {

        /**
         * 默认构造方法。
         */
        public PoolProperties() {
        }

        /**
         * 是否启用连接池（需 commons-pool2）。默认 true。
         */
        private boolean enabled = true;

        /**
         * 最大连接数。默认 16。
         */
        private int maxTotal = 16;

        /**
         * 最大空闲连接。默认 8。
         */
        private int maxIdle = 8;

        /**
         * 最小空闲连接。默认 2。
         */
        private int minIdle = 2;

        /**
         * 获取连接最大等待（毫秒）。默认 2000。
         */
        private long maxWaitMillis = 2000;

    }

}
