package cn.nextdev.lightboot.web.config;

import cn.nextdev.lightboot.web.handler.DatabaseExceptionHandler;
import cn.nextdev.lightboot.web.handler.GlobalExceptionHandler;
import cn.nextdev.lightboot.web.handler.SaTokenExceptionHandler;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Web 模块通用自动配置：注册 WebProperties、HttpStatusCodeResolver 与异常处理器。
 *
 * <p>异常处理器（{@link GlobalExceptionHandler}、{@link DatabaseExceptionHandler}、
 * {@link SaTokenExceptionHandler}）以 {@code @Bean} 方式注册，并通过
 * {@code @ConditionalOnClass}/{@code @ConditionalOnMissingBean} 控制条件注册；
 * advice 的优先级由各处理器类上的 {@code @Order} 决定。
 */
@AutoConfiguration
@EnableConfigurationProperties(WebProperties.class)
public class WebAutoConfiguration {

    /**
     * 默认构造方法。
     */
    public WebAutoConfiguration() {
    }

    /**
     * 注册 HTTP 状态码解析器。
     *
     * @param properties Web 配置属性
     * @return HttpStatusCodeResolver 实例
     */
    @Bean
    public HttpStatusCodeResolver httpStatusCodeResolver(WebProperties properties) {
        return new HttpStatusCodeResolver(properties);
    }

    /**
     * 注册全局兜底异常处理器（最低优先级，作为 catch-all）。
     *
     * <p>当用户未自定义 {@link GlobalExceptionHandler} 时生效。advice 优先级由
     * {@link GlobalExceptionHandler} 类上的 {@code @Order} 决定。
     *
     * @param statusCodeResolver HTTP 状态码解析器
     * @return GlobalExceptionHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler(HttpStatusCodeResolver statusCodeResolver) {
        return new GlobalExceptionHandler(statusCodeResolver);
    }

    /**
     * 注册数据库访问异常处理器（最高优先级）。仅当类路径存在 Spring DAO 异常体系时生效。
     *
     * <p>advice 优先级由 {@link DatabaseExceptionHandler} 类上的 {@code @Order} 决定。
     *
     * @param statusCodeResolver HTTP 状态码解析器
     * @return DatabaseExceptionHandler 实例
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
    @ConditionalOnMissingBean(DatabaseExceptionHandler.class)
    public DatabaseExceptionHandler databaseExceptionHandler(HttpStatusCodeResolver statusCodeResolver) {
        return new DatabaseExceptionHandler(statusCodeResolver);
    }

    /**
     * 注册 SaToken 认证授权异常处理器（最高优先级）。仅当类路径存在 sa-token-core 时生效。
     *
     * <p>advice 优先级由 {@link SaTokenExceptionHandler} 类上的 {@code @Order} 决定。
     *
     * @param statusCodeResolver HTTP 状态码解析器
     * @return SaTokenExceptionHandler 实例
     */
    @Bean
    @ConditionalOnClass(name = "cn.dev33.satoken.exception.SaTokenException")
    @ConditionalOnMissingBean(SaTokenExceptionHandler.class)
    public SaTokenExceptionHandler saTokenExceptionHandler(HttpStatusCodeResolver statusCodeResolver) {
        return new SaTokenExceptionHandler(statusCodeResolver);
    }
}
