package cn.nextdev.lightboot.web.http;

import cn.nextdev.lightboot.web.config.WebProperties;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 将业务 {@code Result.code} 解析为真实 HTTP 状态码
 * （异常对象由 {@code GlobalExceptionHandler} 先提取业务码，再传入本类解析）。
 *
 * <p>映射规则：使用 <b>显式 {@code Map<Long, Integer>}</b>（key 为完整业务码，非百位前缀）
 * 将已知业务码精确映射到对应的 HTTP 状态码，避免 {@code /100} 前缀启发式在新增码时的歧义：
 * <ul>
 *   <li>0（成功）→ 200</li>
 *   <li>10001（认证失败）→ 401</li>
 *   <li>40000 → 400，40100 → 401，40300 → 403，40400 → 404，40900 → 409（数据冲突）</li>
 *   <li>42901 → 429（限流），41300 → 413（文件超限）</li>
 *   <li>50000 → 500，-1 → 500</li>
 *   <li>未在表中的业务码 → 500（保守默认）</li>
 * </ul>
 *
 * <p>当 {@code light-boot.web.http-status.enabled=false} 时一律返回 200。
 */
public class HttpStatusCodeResolver {

    /**
     * 已知业务码 → HTTP 状态码的显式映射表。
     *
     * <p>新增业务码时必须在此显式登记，不再依赖百位前缀推断。
     * Key 为完整 long 业务码，Value 为对应 HTTP 状态码。
     */
    private static final Map<Long, Integer> CODE_TO_HTTP_STATUS = Map.ofEntries(
            Map.entry(10001L, HttpStatus.UNAUTHORIZED.value()), // AUTH_FAILED
            Map.entry(40000L, HttpStatus.BAD_REQUEST.value()), // BAD_REQUEST
            Map.entry(40100L, HttpStatus.UNAUTHORIZED.value()), // UNAUTHORIZED
            Map.entry(40300L, HttpStatus.FORBIDDEN.value()), // FORBIDDEN
            Map.entry(40400L, HttpStatus.NOT_FOUND.value()), // NOT_FOUND
            Map.entry(40900L, HttpStatus.CONFLICT.value()), // CONFLICT（数据冲突，唯一约束冲突）
            Map.entry(41300L, HttpStatus.CONTENT_TOO_LARGE.value()), // CONTENT_TOO_LARGE（文件超限）
            Map.entry(42901L, HttpStatus.TOO_MANY_REQUESTS.value()), // 限流（RateLimitResultCode.RATE_LIMIT）
            Map.entry(50000L, HttpStatus.INTERNAL_SERVER_ERROR.value()), // INTERNAL_ERROR
            Map.entry(-1L, HttpStatus.INTERNAL_SERVER_ERROR.value()) // 兜底失败码
    );

    /**
     * 未知业务码的保守默认状态码。
     */
    private static final int DEFAULT_HTTP_STATUS = HttpStatus.INTERNAL_SERVER_ERROR.value();

    private final WebProperties properties;

    /**
     * 创建状态码解析器。
     *
     * @param properties Web 配置属性（读取 http-status.enabled 开关）
     */
    public HttpStatusCodeResolver(WebProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据业务 {@code Result.code} 解析 HTTP 状态码。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>开关关闭 → 200</li>
     *   <li>成功码 0 → 200</li>
     *   <li>显式映射表命中 → 对应 HTTP 状态码</li>
     *   <li>未命中 → 500（保守默认）</li>
     * </ol>
     *
     * @param resultCode 业务状态码
     * @return HTTP 状态码
     */
    public int resolve(long resultCode) {
        if (!properties.getHttpStatus().isEnabled()) {
            return HttpStatus.OK.value();
        }
        if (resultCode == 0) {
            return HttpStatus.OK.value();
        }
        return CODE_TO_HTTP_STATUS.getOrDefault(resultCode, DEFAULT_HTTP_STATUS);
    }
}
