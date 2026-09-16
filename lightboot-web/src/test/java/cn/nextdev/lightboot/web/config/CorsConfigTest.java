package cn.nextdev.lightboot.web.config;

import cn.nextdev.lightboot.web.config.CorsConfig.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CorsConfig 安全校验单测：fail-fast（credentials + 通配源）与 enabled 默认值。
 */
class CorsConfigTest {

    /**
     * enabled 默认值为 false。
     */
    @Test
    void enabledDefaultsToFalse() {
        assertThat(new CorsProperties().getEnabled()).isFalse();
    }

    /**
     * enabled=true 时过滤器 Bean 必须注册在 {@code corsFilter} 名称下——
     * Spring Security 的 {@code cors()} 按此名称查找 CorsFilter 类型 Bean 并复用
     * （历史名 corsConfigurationSource 会因类型不匹配导致 Security 装配失败）。
     */
    @Test
    void corsFilterBeanExposedUnderSecurityCompatibleName() {
        new ApplicationContextRunner()
                .withUserConfiguration(CorsConfig.class)
                .withPropertyValues("light-boot.cors.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("corsFilter");
                    assertThat(context.getBean("corsFilter")).isInstanceOf(CorsFilter.class);
                });
    }

    /**
     * 默认（未显式开启）不注册任何 CORS Bean。
     */
    @Test
    void corsBeansNotRegisteredWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(CorsConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean("corsFilter"));
    }

    /**
     * credentials 为 true 且 origins 含通配符时校验抛异常。
     */
    @Test
    void validate_throwsWhenCredentialsTrueAndOriginsHaveWildcard() {
        CorsProperties props = new CorsProperties();
        props.setAllowCredentials(true);
        props.setAllowedOrigins(java.util.List.of("*"));

        assertThatThrownBy(() -> CorsConfig.validate(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-credentials");
    }

    /**
     * credentials 为 true 且使用显式域名时校验通过。
     */
    @Test
    void validate_passesWhenCredentialsTrueAndExplicitDomain() {
        CorsProperties props = new CorsProperties();
        props.setAllowCredentials(true);
        props.setAllowedOrigins(java.util.List.of("https://example.com"));

        CorsConfig.validate(props);  // 不抛异常即通过
    }

    /**
     * credentials 为 false 且 origins 含通配符时校验通过。
     */
    @Test
    void validate_passesWhenCredentialsFalseAndOriginsHaveWildcard() {
        CorsProperties props = new CorsProperties();
        props.setAllowCredentials(false);
        props.setAllowedOrigins(java.util.List.of("*"));

        CorsConfig.validate(props);  // 不抛异常即通过
    }

    /**
     * origins 为空时校验通过。
     */
    @Test
    void validate_passesWhenOriginsEmpty() {
        CorsProperties props = new CorsProperties();
        // 默认 origins 为空列表
        CorsConfig.validate(props);
    }
}
