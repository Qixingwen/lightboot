package cn.nextdev.lightboot.web.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web 模块配置属性（前缀：{@code light-boot.web}）。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "light-boot.web")
public class WebProperties {

    /**
     * 默认构造方法。
     */
    public WebProperties() {
    }

    /**
     * HTTP 状态码相关配置。
     */
    private HttpStatusProperties httpStatus = new HttpStatusProperties();

    /**
     * HTTP 状态码配置。
     */
    @Setter
    @Getter
    public static class HttpStatusProperties {

        /**
         * 默认构造方法。
         */
        public HttpStatusProperties() {
        }

        /**
         * 是否让异常响应携带真实 HTTP 状态码（400/401/403/404/429/500 等）。
         * 默认 true。设为 false 时所有响应退回 HTTP 200，业务结果仍由 {@code Result.code} 区分。
         */
        private boolean enabled = true;
    }
}
