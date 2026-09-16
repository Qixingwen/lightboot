package cn.nextdev.lightboot.logging.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 日志模块配置属性（前缀：{@code light-boot.logging}）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "light-boot.logging")
public class LoggingProperties {

    /**
     * 默认构造方法。
     */
    public LoggingProperties() {
    }

    /**
     * 是否启用日志模块，默认 true。
     */
    private boolean enabled = true;

    /**
     * TraceId 配置。
     */
    private Trace trace = new Trace();

    /**
     * 请求日志配置。
     */
    private Request request = new Request();

    /**
     * 脱敏配置。
     */
    private Mask mask = new Mask();

    /**
     * TraceId 配置属性。
     */
    @Getter
    @Setter
    public static class Trace {

        /**
         * 默认构造方法。
         */
        public Trace() {
        }

        /**
         * 是否启用 TraceId 过滤器，默认 true。
         */
        private boolean enabled = true;

        /**
         * TraceId 请求头名称，默认 X-Trace-Id。
         */
        private String headerName = "X-Trace-Id";
    }

    /**
     * 请求日志配置属性。
     */
    @Getter
    @Setter
    public static class Request {

        /**
         * 默认构造方法。
         */
        public Request() {
        }

        /**
         * 是否启用请求日志，默认 true。
         */
        private boolean enabled = true;

        /**
         * 排除路径（Ant 风格），匹配的路径不记录日志。
         */
        private List<String> excludePaths = List.of("/actuator/**", "/favicon.ico");

        /**
         * 慢请求阈值（毫秒）。请求耗时<b>达到或超过</b>此值时，日志级别提升为 WARN 并打 SLOW 标记。
         * 默认 1000ms。设为 0 或负值表示禁用慢请求标记（一律 INFO）。
         */
        private long slowThresholdMs = 1000L;

        /**
         * 受信代理 CIDR 列表。
         *
         * <p>仅当请求的直连 remoteAddr 命中此列表时，才采信 X-Forwarded-For / X-Real-IP；
         * 否则使用 remoteAddr，避免客户端伪造转发头。默认空（不信任任何转发头）。
         */
        private List<String> trustedProxies = List.of();
    }

    /**
     * 脱敏配置属性。
     */
    @Getter
    @Setter
    public static class Mask {

        /**
         * 默认构造方法。
         */
        public Mask() {
        }

        /**
         * 是否启用日志脱敏，默认 true。
         */
        private boolean enabled = true;

        /**
         * 是否已由使用方在 {@code logback-spring.xml} 完成 {@code %maskMsg}/{@code %maskEx} 接线，默认 false。
         * 置 {@code true} 即声明已完成接线，从而抑制 {@link cn.nextdev.lightboot.logging.mask.MaskingStartupLogger}
         * 的启动 WARN；未实际接线却置 {@code true} 会掩盖「应用日志未脱敏」的事实。
         */
        private boolean wired = false;
    }
}
