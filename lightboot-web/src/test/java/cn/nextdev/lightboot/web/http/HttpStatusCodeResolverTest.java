package cn.nextdev.lightboot.web.http;

import cn.nextdev.lightboot.web.config.WebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpStatusCodeResolver 单测：验证 Result.code → HTTP 状态码映射，及 enabled=false 退回 200。
 */
class HttpStatusCodeResolverTest {

    private WebProperties properties;
    private HttpStatusCodeResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new WebProperties();
        resolver = new HttpStatusCodeResolver(properties);
    }

    /**
     * 成功码 0 映射为 HTTP 200。
     */
    @Test
    void resolve_successCode0Returns200() {
        assertThat(resolver.resolve(0L)).isEqualTo(200);
    }

    /**
     * 参数错误码 40000 映射为 HTTP 400。
     */
    @Test
    void resolve_paramErrorCode40000Returns400() {
        assertThat(resolver.resolve(40000L)).isEqualTo(400);
    }

    /**
     * 认证失败码 10001 映射为 HTTP 401。
     */
    @Test
    void resolve_authFailureCode10001Returns401() {
        assertThat(resolver.resolve(10001L)).isEqualTo(401);
    }

    /**
     * 未登录码 40100 映射为 HTTP 401。
     */
    @Test
    void resolve_notLoggedInCode40100Returns401() {
        assertThat(resolver.resolve(40100L)).isEqualTo(401);
    }

    /**
     * 无权限码 40300 映射为 HTTP 403。
     */
    @Test
    void resolve_forbiddenCode40300Returns403() {
        assertThat(resolver.resolve(40300L)).isEqualTo(403);
    }

    /**
     * 不存在码 40400 映射为 HTTP 404。
     */
    @Test
    void resolve_notFoundCode40400Returns404() {
        assertThat(resolver.resolve(40400L)).isEqualTo(404);
    }

    /**
     * 数据冲突码 40900 映射为 HTTP 409。
     */
    @Test
    void resolve_conflictCode40900Returns409() {
        assertThat(resolver.resolve(40900L)).isEqualTo(409);
    }

    /**
     * 限流码 42901 映射为 HTTP 429。
     */
    @Test
    void resolve_rateLimitCode42901Returns429() {
        assertThat(resolver.resolve(42901L)).isEqualTo(429);
    }

    /**
     * 文件超限码 41300 映射为 HTTP 413。
     */
    @Test
    void resolve_payloadTooLargeCode41300Returns413() {
        assertThat(resolver.resolve(41300L)).isEqualTo(413);
    }

    /**
     * 系统错误码 50000 映射为 HTTP 500。
     */
    @Test
    void resolve_systemErrorCode50000Returns500() {
        assertThat(resolver.resolve(50000L)).isEqualTo(500);
    }

    /**
     * 兜底失败码 -1 映射为 HTTP 500。
     */
    @Test
    void resolve_fallbackFailureCodeMinusOneReturns500() {
        assertThat(resolver.resolve(-1L)).isEqualTo(500);
    }

    /**
     * 未知业务码映射为 HTTP 500。
     */
    @Test
    void resolve_unknownCodeReturns500() {
        assertThat(resolver.resolve(99999L)).isEqualTo(500);
    }

    /**
     * enabled 为 false 时所有业务码一律映射为 HTTP 200。
     */
    @Test
    void resolve_returns200ForAllCodesWhenDisabled() {
        properties.getHttpStatus().setEnabled(false);
        assertThat(resolver.resolve(40000L)).isEqualTo(200);
        assertThat(resolver.resolve(50000L)).isEqualTo(200);
        assertThat(resolver.resolve(-1L)).isEqualTo(200);
    }
}
