package cn.nextdev.lightboot.web.config;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域（CORS）全局配置，同时支持 Spring MVC 与 Spring Security 两条链路。
 *
 * <p>通过 {@code application.yml} 以 {@code light-boot.cors} 为前缀进行配置
 * （{@code enabled} 默认 {@code false}，必须显式开启）：
 * <pre>{@code
 * light-boot:
 *   cors:
 *     enabled: true
 *     allowed-origins: http://localhost:3000,https://yourdomain.com
 *     allowed-methods: GET,POST,PUT,DELETE,OPTIONS
 *     allow-credentials: true
 *     max-age: 3600
 * }</pre>
 *
 * <p><b>生产环境最佳实践：</b>
 * <ul>
 *   <li>必须使用显式域名，禁止使用通配符 {@code "*"}</li>
 *   <li>{@code allow-credentials: true} 时不可使用通配符源</li>
 *   <li>建议为开发环境和生产环境分别配置</li>
 * </ul>
 */
@AutoConfiguration
public class CorsConfig {

    /**
     * 默认构造方法。
     */
    public CorsConfig() {
    }

    /**
     * 默认预检请求缓存时间：1 小时（3600 秒）
     */
    private static final Long DEFAULT_MAX_AGE = 3600L;

    /**
     * 默认 CORS 路径匹配模式：所有路径
     */
    private static final String DEFAULT_PATH_PATTERN = "/**";

    /**
     * 校验 CORS 配置的安全性。
     *
     * <p>fail-fast：当 {@code allow-credentials=true} 且 {@code allowed-origins} 含通配符 {@code "*"} 时，
     * 直接抛 {@link IllegalStateException} 终止启动。这是框架强制的安全约束（通配源无法安全携带
     * 凭证），并非 CORS 规范禁止——Spring 的 origin patterns 语义下该组合会被放行并回显请求来源。
     *
     * @param properties CORS 配置属性
     */
    public static void validate(CorsProperties properties) {
        if (Boolean.TRUE.equals(properties.getAllowCredentials())
                && properties.getAllowedOrigins().contains("*")) {
            throw new IllegalStateException(
                    "Invalid CORS config: allow-credentials=true cannot be combined with allowed-origins=\"*\". Specify explicit origins when credentials are allowed.");
        }
    }

    /**
     * Spring MVC 层面的 CORS 映射配置。
     *
     * @param corsProperties CORS 配置属性
     * @return 配置了 CORS 映射的 {@link WebMvcConfigurer}
     */
    @Bean
    @ConditionalOnProperty(prefix = "light-boot.cors", name = "enabled", havingValue = "true", matchIfMissing = false)
    public WebMvcConfigurer corsConfigurer(CorsProperties corsProperties) {
        validate(corsProperties);
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                registry.addMapping(corsProperties.getPathPattern())
                        .allowedOriginPatterns(corsProperties.getAllowedOrigins().toArray(new String[0]))
                        .allowedMethods(corsProperties.getAllowedMethods().toArray(new String[0]))
                        .allowedHeaders(corsProperties.getAllowedHeaders().toArray(new String[0]))
                        .exposedHeaders(corsProperties.getExposedHeaders().toArray(new String[0]))
                        .allowCredentials(corsProperties.getAllowCredentials())
                        .maxAge(corsProperties.getMaxAge());
            }
        };
    }

    /**
     * CORS 过滤器 Bean，供 Servlet 过滤器链与 Spring Security 复用。
     *
     * <p>按 {@code pathPattern} 注册 {@link CorsConfiguration}，封装为 {@link CorsFilter}。
     * Bean 名称固定为 {@code corsFilter}：Spring Security 开启 {@code cors()} 时会优先查找
     * 该名称的 {@link CorsFilter} Bean 并在安全链路中复用；未集成 Security 时，本 Bean 作为
     * 普通 Servlet 过滤器由 Spring Boot 自动注册生效。
     *
     * <p>注意：本 Bean 与 {@link #corsConfigurer(CorsProperties)} 同受
     * {@code light-boot.cors.enabled} 控制，同时生效时同一请求会分别经过过滤器链与
     * MVC 映射两条 CORS 处理路径——集成 Security 的应用建议按链路只保留其一。
     *
     * @param corsProperties CORS 配置属性
     * @return 注册了 CORS 规则的 {@link CorsFilter}
     * @see #corsConfigurer(CorsProperties)
     */
    @Bean
    @ConditionalOnProperty(prefix = "light-boot.cors", name = "enabled", havingValue = "true", matchIfMissing = false)
    public CorsFilter corsFilter(CorsProperties corsProperties) {
        validate(corsProperties);
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(corsProperties.getAllowedMethods());
        configuration.setAllowedHeaders(corsProperties.getAllowedHeaders());
        configuration.setExposedHeaders(corsProperties.getExposedHeaders());
        configuration.setAllowCredentials(corsProperties.getAllowCredentials());
        configuration.setMaxAge(corsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(corsProperties.getPathPattern(), configuration);
        return new CorsFilter(source);
    }

    /**
     * CORS 配置属性 Bean。
     *
     * @return 绑定前缀 {@code light-boot.cors} 的配置属性实例
     */
    @Bean
    @ConfigurationProperties(prefix = "light-boot.cors")
    public CorsProperties corsProperties() {
        return new CorsProperties();
    }

    /**
     * CORS 跨域配置属性，绑定前缀为 {@code light-boot.cors}。
     *
     * <p>所有 Setter 均做了空值安全处理。
     *
     * <p><b>配置示例：</b>
     * <pre>{@code
     * # application-dev.yml（开发环境）
     * light-boot:
     *   cors:
     *     allowed-origins: "*"
     *
     * # application-prod.yml（生产环境）
     * light-boot:
     *   cors:
     *     allowed-origins: https://yourdomain.com
     * }</pre>
     */
    @Getter
    @Setter
    public static class CorsProperties {

        /**
         * 默认构造方法。
         */
        public CorsProperties() {
        }

        /**
         * 允许的跨域请求来源。
         *
         * <p>示例：{@code "*"}、{@code "https://example.com"}、{@code "http://localhost:3000"}
         *
         * <p><b>安全默认值：</b>默认为空列表（不放开任何跨域来源），必须由使用方显式配置。
         * 出于安全考虑，框架不再默认放开通配符 {@code "*"}，避免生产环境无意中暴露开放跨域。
         * 开发环境可显式配置 {@code light-boot.cors.allowed-origins: "*"} 或 {@code "http://localhost:3000"}。
         *
         * <p><b>注意：</b>当 {@code allow-credentials} 为 {@code true} 时，不可使用 {@code "*"}
         */
        private List<String> allowedOrigins = new ArrayList<>();

        /**
         * 允许的 HTTP 方法。
         *
         * <p>常用值：{@code GET}、{@code POST}、{@code PUT}、{@code DELETE}、{@code OPTIONS}
         */
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

        /**
         * 允许的请求头。
         *
         * <p>使用 {@code "*"} 表示允许所有请求头，或显式指定如 {@code "Content-Type"}、{@code "Authorization"}
         */
        private List<String> allowedHeaders = List.of("*");

        /**
         * 允许浏览器访问的响应头。
         *
         * <p>常用值：{@code "Content-Disposition"}、{@code "Authorization"}、{@code "X-Total-Count"}
         */
        private List<String> exposedHeaders = List.of("Content-Disposition", "Authorization");

        /**
         * 是否允许携带凭证（Cookie、认证头等）。
         *
         * <p><b>注意：</b>为 {@code true} 时必须使用显式域名，不可使用通配符 {@code "*"}
         */
        private Boolean allowCredentials = false;

        /**
         * 预检请求响应缓存时间（秒）。
         *
         * <p>建议：开发环境 60-300 秒，生产环境 3600 秒（1 小时）
         *
         * @see CorsConfig#DEFAULT_MAX_AGE
         */
        private Long maxAge = DEFAULT_MAX_AGE;

        /**
         * CORS 配置的 URL 路径匹配模式。
         *
         * <p>默认值：{@code "/**"}（匹配所有路径）
         */
        private String pathPattern = DEFAULT_PATH_PATTERN;

        /**
         * 是否启用 CORS 自动装配。默认 false，需显式开启；设为 true 时注册 CORS 相关 Bean。
         */
        private Boolean enabled = false;

        /**
         * 设置允许的跨域来源（空值安全）。
         *
         * @param allowedOrigins 允许的来源列表，为 {@code null} 时设为空列表
         */
        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins != null ? allowedOrigins : new ArrayList<>();
        }

        /**
         * 设置允许的 HTTP 方法（空值安全）。
         *
         * @param allowedMethods 允许的方法列表，为 {@code null} 时设为空列表
         */
        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods != null ? allowedMethods : new ArrayList<>();
        }

        /**
         * 设置允许的请求头（空值安全）。
         *
         * @param allowedHeaders 允许的请求头列表，为 {@code null} 时设为空列表
         */
        public void setAllowedHeaders(List<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders != null ? allowedHeaders : new ArrayList<>();
        }

        /**
         * 设置允许浏览器访问的响应头（空值安全）。
         *
         * @param exposedHeaders 暴露的响应头列表，为 {@code null} 时设为空列表
         */
        public void setExposedHeaders(List<String> exposedHeaders) {
            this.exposedHeaders = exposedHeaders != null ? exposedHeaders : new ArrayList<>();
        }

        /**
         * 设置是否允许携带凭证（空值安全）。
         *
         * @param allowCredentials 是否允许凭证，为 {@code null} 时默认为 {@code false}
         */
        public void setAllowCredentials(Boolean allowCredentials) {
            this.allowCredentials = allowCredentials != null && allowCredentials;
        }

        /**
         * 设置预检请求缓存时间（空值安全）。
         *
         * @param maxAge 缓存时间（秒），为 {@code null} 时使用默认值
         */
        public void setMaxAge(Long maxAge) {
            this.maxAge = maxAge != null ? maxAge : DEFAULT_MAX_AGE;
        }

        /**
         * 设置路径匹配模式（空值安全）。
         *
         * @param pathPattern URL 路径匹配模式，为 {@code null} 时使用默认值
         */
        public void setPathPattern(String pathPattern) {
            this.pathPattern = pathPattern != null ? pathPattern : DEFAULT_PATH_PATTERN;
        }

        /**
         * 设置是否启用 CORS 自动装配。
         *
         * @param enabled 是否启用，为 {@code null} 时默认为 {@code false}
         */
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled != null && enabled;
        }
    }

}
