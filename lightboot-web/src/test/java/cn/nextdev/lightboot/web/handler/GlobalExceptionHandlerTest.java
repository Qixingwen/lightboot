package cn.nextdev.lightboot.web.handler;

import cn.nextdev.lightboot.core.api.IErrorCode;
import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.core.exception.ApiException;
import cn.nextdev.lightboot.web.config.WebProperties;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.crypto.BadPaddingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 切片测试：验证各类异常被转换为统一 Result，并携带真实 HTTP 状态码。
 * 使用 MockMvc standalone 模式，显式挂载测试 controller 与 GlobalExceptionHandler，
 * 不启动完整 Spring 上下文，也不依赖 sa-token / spring-tx（本模块中均为 optional）。
 *
 * <p>本测试顺带覆盖 core 模块的 Result / ApiException / ResultCode（严格 C 策略下 core 不单独测）。
 */
class GlobalExceptionHandlerTest {

    /**
     * 模拟限流错误码 42901，用于验证 429xx → HTTP 429 映射（不依赖 ratelimit 模块）。
     */
    private static final IErrorCode RATE_LIMIT_CODE = new IErrorCode() {
        @Override
        public long getCode() {
            return 42901L;
        }

        @Override
        public String getMessage() {
            return "请求过于频繁，请稍后重试";
        }
    };

    private MockMvc mockMvc;

    /**
     * 校验用 DTO：name 字段不能为空（触发 MethodArgumentNotValidException / BindException）。
     */
    static class ValidationDto {
        @NotBlank(message = "姓名不能为空")
        public String name;
    }

    /**
     * 测试用 controller，按请求路径抛出不同异常。
     */
    @RestController
    static class TestController {
        @GetMapping("/api/throw/api")
        public void throwApi() {
            throw new ApiException(ResultCode.NOT_FOUND, "资源不存在");
        }

        @GetMapping("/api/throw/api-no-code")
        public void throwApiNoCode() {
            throw new ApiException("自定义失败消息");
        }

        @GetMapping("/api/throw/npe")
        public void throwNpe() {
            throw new NullPointerException("空指针");
        }

        @GetMapping("/api/throw/ratelimit")
        public void throwRateLimit() {
            // 模拟限流异常：携带 42901 错误码的 ApiException（与 RateLimitException 等价）
            throw new ApiException(RATE_LIMIT_CODE);
        }

        @GetMapping("/api/throw/ratelimit-retry")
        public void throwRateLimitWithRetry() {
            // 模拟携带重试窗口的限流异常：覆写 getRetryAfterSeconds 返回正数，
            // 验证 web 模块经基类钩子写入 Retry-After 头（无需依赖 ratelimit 模块）。
            throw new ApiException(RATE_LIMIT_CODE) {
                @Override
                public long getRetryAfterSeconds() {
                    return 60L;
                }
            };
        }

        @GetMapping("/api/throw/upload-size")
        public void throwUploadSizeExceeded() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(65536L);
        }

        @PostMapping("/api/throw/valid-body")
        public void throwValidation(@Valid @RequestBody ValidationDto dto) {
            // @Valid @RequestBody 校验失败 → MethodArgumentNotValidException
        }

        @PostMapping("/api/throw/bind")
        public void throwBind(@Valid @ModelAttribute ValidationDto dto) {
            // @Valid 表单绑定校验失败 → BindException
        }

        @GetMapping("/api/param/type-mismatch")
        public void throwTypeMismatch(@RequestParam Long id) {
            // id=abc → MethodArgumentTypeMismatchException
        }

        @GetMapping("/api/param/header")
        public void throwMissingHeader(@RequestHeader("X-Api-Key") String apiKey) {
            // 缺少 X-Api-Key → MissingRequestHeaderException
        }

        @GetMapping("/api/param/required")
        public void throwMissingParam(@RequestParam String q) {
            // 缺少 q → MissingServletRequestParameterException
        }

        @PostMapping("/api/throw/not-readable")
        public void throwNotReadable(@RequestBody ValidationDto dto) {
            // 畸形 JSON → HttpMessageNotReadableException
        }

        @PostMapping("/api/throw/media-type")
        public void throwMediaType(@RequestBody ValidationDto dto) {
            // text/plain 请求体 → HttpMediaTypeNotSupportedException
        }

        @GetMapping("/api/throw/no-handler")
        public void throwNoHandlerFound() throws NoHandlerFoundException {
            // standalone MockMvc 无 handler 的请求由静态资源处理器接管（NoResourceFoundException），
            // NoHandlerFoundException 只能直接抛出以验证 handler 分支
            throw new NoHandlerFoundException("GET", "/api/throw/no-handler", new HttpHeaders());
        }

        @GetMapping("/api/throw/io")
        public void throwIo() throws IOException {
            throw new IOException("磁盘读写失败");
        }

        @GetMapping("/api/throw/bad-padding")
        public void throwBadPadding() throws BadPaddingException {
            throw new BadPaddingException("填充校验失败");
        }

        @GetMapping("/api/throw/illegal-argument")
        public void throwIllegalArgument() {
            throw new IllegalArgumentException("非法参数");
        }

        @GetMapping("/api/throw/illegal-state")
        public void throwIllegalState() {
            throw new IllegalStateException("非法状态");
        }

        @GetMapping("/api/throw/runtime")
        public void throwRuntime() {
            // 非上述任何具体类型的 RuntimeException，命中兜底分支
            throw new UnsupportedOperationException("不支持的操作");
        }

        @GetMapping("/api/throw/checked")
        public void throwChecked() throws Exception {
            // 受检异常命中 Exception 兜底分支
            throw new Exception("顶层异常");
        }

        @GetMapping("/api/ok")
        public Object ok() {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        WebProperties props = new WebProperties();
        HttpStatusCodeResolver resolver = new HttpStatusCodeResolver(props);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(resolver))
                .build();
    }

    /**
     * 带错误码的 ApiException 映射为对应的 code 与 message。
     */
    @Test
    void apiExceptionWithCodeMapsToCorrespondingCodeAndMessage() throws Exception {
        mockMvc.perform(get("/api/throw/api"))
                .andExpect(status().isNotFound()) // NOT_FOUND 40400 → HTTP 404
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    /**
     * 无错误码的 ApiException 直接使用消息（code 为 -1）。
     */
    @Test
    void apiExceptionWithoutCodeUsesMessage() throws Exception {
        mockMvc.perform(get("/api/throw/api-no-code"))
                .andExpect(status().is5xxServerError()) // -1 → HTTP 500
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("自定义失败消息"));
    }

    /**
     * NullPointerException 映射为系统内部错误。
     */
    @Test
    void nullPointerMapsToInternalError() throws Exception {
        mockMvc.perform(get("/api/throw/npe"))
                .andExpect(status().is5xxServerError()) // INTERNAL_ERROR 50000 → HTTP 500
                .andExpect(jsonPath("$.code").value(ResultCode.INTERNAL_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("系统内部错误"));
    }

    /**
     * 携带 429xx 错误码的 ApiException 映射为 HTTP 429（限流）。
     *
     * <p>覆盖限流场景：42901 命中 HttpStatusCodeResolver 显式映射表 → HTTP 429。
     */
    @Test
    void apiExceptionWithRateLimitCodeMapsTo429() throws Exception {
        mockMvc.perform(get("/api/throw/ratelimit"))
                .andExpect(status().isTooManyRequests()) // 42901 → HTTP 429
                .andExpect(jsonPath("$.code").value(42901L))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后重试"))
                // 普通 ApiException 不携带 retry-after（基类默认 0），不应写入头
                .andExpect(header().doesNotExist("Retry-After"));
    }

    /**
     * 携带重试窗口秒数的 ApiException（如限流异常）在 429 响应中写入 Retry-After 头。
     *
     * <p>覆盖与 ratelimit 模块的解耦契约：web 模块仅依赖基类 {@code ApiException#getRetryAfterSeconds}，
     * 子类（RateLimitException）通过覆写该钩子注入窗口秒数，无需 web 反向依赖 ratelimit。
     */
    @Test
    void rateLimitWithRetryAfterAddsHeader() throws Exception {
        mockMvc.perform(get("/api/throw/ratelimit-retry"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901L))
                .andExpect(header().string("Retry-After", "60"));
    }

    /**
     * 正常请求不被异常处理器拦截，直接返回 200。
     */
    @Test
    void normalRequestNotInterceptedByHandler() throws Exception {
        mockMvc.perform(get("/api/ok"))
                .andExpect(status().isOk());
    }

    /**
     * 上传大小超限映射为 FILE_TOO_LARGE(41300) 与 HTTP 413——
     * 锁定「HTTP 状态由响应体 Result.code 映射」这一类级契约（body code 与状态码必须一致）。
     */
    @Test
    void maxUploadSizeMapsTo413WithMatchingBodyCode() throws Exception {
        mockMvc.perform(get("/api/throw/upload-size"))
                .andExpect(status().isContentTooLarge()) // FILE_TOO_LARGE 41300 → HTTP 413
                .andExpect(jsonPath("$.code").value(ResultCode.FILE_TOO_LARGE.getCode()))
                .andExpect(jsonPath("$.message").value("文件大小超过限制"));
    }

    /**
     * httpStatus 禁用时异常响应退回 HTTP 200。
     */
    @Test
    void exceptionResponseFallsBackTo200WhenHttpStatusDisabled() throws Exception {
        WebProperties props = new WebProperties();
        props.getHttpStatus().setEnabled(false);
        HttpStatusCodeResolver resolver = new HttpStatusCodeResolver(props);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(resolver))
                .build();

        mockMvc.perform(get("/api/throw/api"))
                .andExpect(status().isOk()) // 退回 200
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));
    }

    /**
     * {@code @Valid @RequestBody} 校验失败（MethodArgumentNotValidException）映射为 BAD_REQUEST(40000)，
     * message 按「字段名: 校验消息」格式拼接。
     */
    @Test
    void validationFailureMapsTo400WithFieldMessage() throws Exception {
        mockMvc.perform(post("/api/throw/valid-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest()) // BAD_REQUEST 40000 → HTTP 400
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("name: 姓名不能为空"));
    }

    /**
     * {@code @Valid @ModelAttribute} 表单绑定校验失败（BindException）与 Body 校验走同一 handler，
     * message 同样按「字段名: 校验消息」格式。
     */
    @Test
    void formBindFailureMapsTo400WithFieldMessage() throws Exception {
        mockMvc.perform(post("/api/throw/bind")
                        .param("name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("name: 姓名不能为空"));
    }

    /**
     * 请求参数类型不匹配映射为 BAD_REQUEST(40000)，message 含参数名与期望类型。
     */
    @Test
    void typeMismatchMapsTo400WithParamAndExpectedType() throws Exception {
        mockMvc.perform(get("/api/param/type-mismatch").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("参数类型错误: 'id' 应为 Long 类型"));
    }

    /**
     * 缺少必需请求头映射为 BAD_REQUEST(40000)，message 含缺失头名。
     */
    @Test
    void missingHeaderMapsTo400WithHeaderName() throws Exception {
        mockMvc.perform(get("/api/param/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("缺少必需请求头: X-Api-Key"));
    }

    /**
     * 缺少必需请求参数映射为 BAD_REQUEST(40000)，message 含缺失参数名。
     */
    @Test
    void missingParamMapsTo400WithParamName() throws Exception {
        mockMvc.perform(get("/api/param/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("缺少必需参数: q"));
    }

    /**
     * 畸形 JSON 请求体映射为 BAD_REQUEST(40000) 与固定文案，不泄露解析细节。
     */
    @Test
    void malformedJsonBodyMapsTo400WithFixedMessage() throws Exception {
        mockMvc.perform(post("/api/throw/not-readable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("请求体格式错误或数据类型不匹配"));
    }

    /**
     * 不支持的请求方法映射为 BAD_REQUEST(40000)，message 含当前方法与支持方法列表。
     *
     * <p>注意：业务码为 BAD_REQUEST（非 405xx），HTTP 状态由响应体 code 映射为 400 而非 405。
     */
    @Test
    void methodNotSupportedMapsTo400WithSupportedList() throws Exception {
        mockMvc.perform(post("/api/ok")) // /api/ok 仅为 GET
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("不支持的请求方法: POST，支持的方法: GET"));
    }

    /**
     * 不支持的媒体类型映射为 BAD_REQUEST(40000)，message 含请求 Content-Type。
     */
    @Test
    void unsupportedMediaTypeMapsTo400WithContentType() throws Exception {
        mockMvc.perform(post("/api/throw/media-type")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("不支持的媒体类型: text/plain"));
    }

    /**
     * IOException 映射为 FAILED(-1)、固定文案「文件读写异常」与 HTTP 500。
     */
    @Test
    void ioExceptionMapsTo500WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/throw/io"))
                .andExpect(status().isInternalServerError()) // -1 → HTTP 500
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("文件读写异常"));
    }

    /**
     * BadPaddingException 映射为 FAILED(-1)、固定文案「数据解密异常」与 HTTP 500。
     */
    @Test
    void badPaddingMapsTo500WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/throw/bad-padding"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("数据解密异常"));
    }

    /**
     * NoHandlerFoundException 映射为 NOT_FOUND(40400)、固定文案「请求的资源不存在」与 HTTP 404。
     */
    @Test
    void noHandlerFoundMapsTo404WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/throw/no-handler"))
                .andExpect(status().isNotFound()) // NOT_FOUND 40400 → HTTP 404
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"));
    }

    /**
     * 未知路径（无 controller 命中，落入静态资源处理器）抛出的 NoResourceFoundException
     * 同样映射为 NOT_FOUND(40400) 与 HTTP 404。
     */
    @Test
    void unknownPathMapsTo404ViaNoResourceFound() throws Exception {
        mockMvc.perform(get("/api/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"));
    }

    /**
     * IllegalArgumentException 映射为 BAD_REQUEST(40000) 与固定文案「请求参数不合法」。
     */
    @Test
    void illegalArgumentMapsTo400WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/throw/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
    }

    /**
     * IllegalStateException 映射为 FAILED(-1)、固定文案「系统状态异常」与 HTTP 500。
     */
    @Test
    void illegalStateMapsTo500WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/throw/illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("系统状态异常"));
    }

    /**
     * 未被具体 handler 覆盖的 RuntimeException 命中兜底分支：FAILED(-1)、「运行时处理异常」与 HTTP 500。
     */
    @Test
    void unhandledRuntimeMapsTo500WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/throw/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("运行时处理异常"));
    }

    /**
     * 未被 RuntimeException 分支覆盖的受检异常命中 Exception 兜底分支：
     * FAILED(-1)、「系统异常，请稍后重试」与 HTTP 500。
     */
    @Test
    void checkedExceptionFallsBackTo500WithFixedMessage() throws Exception {
        mockMvc.perform(get("/api/throw/checked"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("系统异常，请稍后重试"));
    }
}
