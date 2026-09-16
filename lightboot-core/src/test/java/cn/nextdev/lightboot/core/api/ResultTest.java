package cn.nextdev.lightboot.core.api;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Result 类的单元测试。
 */
class ResultTest {

    @Test
    void isSuccess_trueForSuccessFactory() {
        Result<String> result = Result.success();
        assertTrue(result.isSuccess(), "Result.success() 应该返回 isSuccess() = true");
        assertFalse(result.failed(), "Result.success() 应该返回 failed() = false");
    }

    @Test
    void isSuccess_trueForSuccessFactoryWithData() {
        Result<String> result = Result.success("data");
        assertTrue(result.isSuccess(), "Result.success(data) 应该返回 isSuccess() = true");
        assertFalse(result.failed(), "Result.success(data) 应该返回 failed() = false");
    }

    @Test
    void isSuccess_falseForFailedFactory() {
        Result<String> result = Result.failed("error message");
        assertFalse(result.isSuccess(), "Result.failed(message) 应该返回 isSuccess() = false");
        assertTrue(result.failed(), "Result.failed(message) 应该返回 failed() = true");
    }

    @Test
    void isSuccess_falseForFailedFactoryWithErrorCode() {
        Result<String> result = Result.failed(ResultCode.BAD_REQUEST);
        assertFalse(result.isSuccess(), "Result.failed(errorCode) 应该返回 isSuccess() = false");
        assertTrue(result.failed(), "Result.failed(errorCode) 应该返回 failed() = true");
    }

    @Test
    void equals_andHashCode_matchForEqualResults() {
        Result<String> result1 = Result.success("data");
        Result<String> result2 = Result.success("data");

        assertEquals(result1, result2, "两个相同内容 Result 对象应该相等");
        assertEquals(result1.hashCode(), result2.hashCode(), "两个相等对象的 hashCode 应该相同");
    }

    @Test
    void equals_andHashCode_differForDifferentResults() {
        Result<String> result1 = Result.success("data1");
        Result<String> result2 = Result.success("data2");

        assertNotEquals(result1, result2, "两个不同内容 Result 对象不应该相等");
    }

    @Test
    void toString_containsFields() {
        Result<String> result = Result.failed("err");
        String toString = result.toString();

        // toString 应该包含字段信息，而不是默认的 Result@hash 格式
        assertFalse(toString.matches("^Result@\\[[a-f0-9]+]$"), "toString 不应该是默认的 Object@hash 格式");
        assertTrue(toString.length() > 10, "toString 应该包含实际的字段信息，长度应该大于10");
    }

    @Test
    void toString_includesCodeAndMessage() {
        Result<String> result = Result.failed("test error");
        String toString = result.toString();

        // toString 应该包含 code 和 message 信息
        assertTrue(toString.contains("test error") || toString.contains("err"), "toString 应该包含错误消息");
    }

    @Test
    void isSuccess_returnsFalseForNonZeroSuccessCode() {
        // 测试边界情况：当使用 success(IErrorCode) 且 code 为非零值时，isSuccess() 应该返回 false
        // 这符合设计要求：success() 严格定义为 code == 0
        Result<String> result = Result.success(ResultCode.BAD_REQUEST);
        assertFalse(result.isSuccess(), "success(IErrorCode) 当 code 不为 0 时，isSuccess() 应该返回 false（严格定义）");
    }

    /**
     * 合法 traceId（32 位小写 hex，满足 TraceFilter 校验契约 ^[a-f0-9]{16,64}$）。
     */
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    /**
     * MDC 设置 traceId 后，所有静态工厂创建的 Result 均自动携带。
     */
    @Test
    void traceId_filledFromMdcForAllFactories() {
        MDC.put("traceId", TRACE_ID);
        try {
            assertEquals(TRACE_ID, Result.success().getTraceId(), "success() 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.success("data").getTraceId(), "success(data) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.success("data", "ok").getTraceId(), "success(data, message) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.success(ResultCode.SUCCESS).getTraceId(), "success(errorCode) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.<String>success(ResultCode.SUCCESS, "data").getTraceId(), "success(errorCode, data) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.failed("err").getTraceId(), "failed(message) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.failed(ResultCode.BAD_REQUEST).getTraceId(), "failed(errorCode) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.failed(ResultCode.BAD_REQUEST, "err").getTraceId(), "failed(errorCode, message) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.failed(ResultCode.BAD_REQUEST, 123).getTraceId(), "failed(errorCode, data) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.badRequest("bad").getTraceId(), "badRequest(message) 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.unauthorized().getTraceId(), "unauthorized() 应携带 MDC traceId");
            assertEquals(TRACE_ID, Result.forbidden().getTraceId(), "forbidden() 应携带 MDC traceId");
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * MDC 无值时 traceId 为 null（含异步链路断裂、非 web 场景）。
     */
    @Test
    void traceId_nullWhenMdcAbsent() {
        MDC.remove("traceId");
        assertNull(Result.success().getTraceId(), "MDC 无 traceId 时 getTraceId() 应返回 null");
    }

    /**
     * traceId 是传输上下文而非逻辑值：仅 traceId 不同的两个 Result 应判定相等。
     */
    @Test
    void equals_ignoresTraceId() {
        MDC.put("traceId", TRACE_ID);
        try {
            Result<String> withTrace = Result.success("data");
            MDC.remove("traceId");
            Result<String> withoutTrace = Result.success("data");
            assertEquals(withTrace, withoutTrace, "仅 traceId 不同的 Result 应相等（不参与 equals）");
            assertEquals(withTrace.hashCode(), withoutTrace.hashCode(), "仅 traceId 不同时 hashCode 应相同");
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * MDC 有值时，JSON 序列化输出 traceId。
     */
    @Test
    void serialization_includesTraceIdWhenPresent() {
        MDC.put("traceId", TRACE_ID);
        try {
            String json = JsonMapper.builder().build().writeValueAsString(Result.success("data"));
            assertTrue(json.contains("\"traceId\":\"" + TRACE_ID + "\""), "MDC 有值时 JSON 应包含 traceId");
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * MDC 无值时，JSON 序列化整体省略 traceId 键（而非输出 null）。
     */
    @Test
    void serialization_omitsTraceIdWhenAbsent() {
        MDC.remove("traceId");
        String json = JsonMapper.builder().build().writeValueAsString(Result.success("data"));
        assertFalse(json.contains("traceId"), "MDC 无值时 JSON 不应包含 traceId 键");
    }
}
