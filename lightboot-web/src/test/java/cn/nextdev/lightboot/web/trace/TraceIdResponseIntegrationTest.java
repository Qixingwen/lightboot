package cn.nextdev.lightboot.web.trace;

import cn.nextdev.lightboot.core.api.Result;
import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.core.exception.ApiException;
import cn.nextdev.lightboot.logging.config.LoggingProperties;
import cn.nextdev.lightboot.logging.trace.TraceContext;
import cn.nextdev.lightboot.logging.trace.TraceFilter;
import cn.nextdev.lightboot.web.config.WebProperties;
import cn.nextdev.lightboot.web.handler.GlobalExceptionHandler;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TraceFilter → Result.traceId 端到端集成测试。
 *
 * <p>验证 web 请求经 TraceFilter 注入 TraceId 后，统一响应 Result 的 traceId 字段
 * 与响应头一致；未携带请求头时自动生成。standalone MockMvc 模式，不启动完整上下文。
 */
class TraceIdResponseIntegrationTest {

    private MockMvc mockMvc;

    /**
     * 与生产一致的 TraceId 请求/响应头名称（来自 LoggingProperties 默认配置）。
     */
    private String headerName;

    /**
     * 测试用 controller，返回统一 Result。
     */
    @RestController
    static class TestController {
        @GetMapping("/api/trace/ok")
        public Result<String> ok() {
            return Result.success("ok");
        }

        @GetMapping("/api/trace/throw")
        public void throwApi() {
            throw new ApiException(ResultCode.NOT_FOUND, "资源不存在");
        }
    }

    @BeforeEach
    void setUp() {
        LoggingProperties props = new LoggingProperties();
        headerName = props.getTrace().getHeaderName();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(new HttpStatusCodeResolver(new WebProperties())))
                .addFilters(new TraceFilter(headerName))
                .build();
    }

    /**
     * 上游传入合法 TraceId 时，响应 JSON 的 traceId 与响应头一致（透传场景）。
     */
    @Test
    void resultCarriesUpstreamTraceId() throws Exception {
        String traceId = TraceContext.generate();
        mockMvc.perform(get("/api/trace/ok").header(headerName, traceId))
                .andExpect(status().isOk())
                .andExpect(header().string(headerName, traceId))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    /**
     * 未携带 TraceId 请求头时，TraceFilter 自动生成，Result.traceId 为 32 位纯 hex。
     */
    @Test
    void resultCarriesGeneratedTraceIdWhenHeaderAbsent() throws Exception {
        mockMvc.perform(get("/api/trace/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(matchesPattern("^[a-f0-9]{32}$")));
    }

    /**
     * 错误链路端到端：异常经 GlobalExceptionHandler 转为 Result.failed 后仍携带 TraceId，
     * 与响应头一致（spec 动机：前端拿到错误响应后直接关联日志）。
     */
    @Test
    void errorResponseCarriesTraceId() throws Exception {
        String traceId = TraceContext.generate();
        mockMvc.perform(get("/api/trace/throw").header(headerName, traceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }
}
