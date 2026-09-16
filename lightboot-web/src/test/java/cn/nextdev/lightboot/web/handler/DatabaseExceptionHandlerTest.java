package cn.nextdev.lightboot.web.handler;

import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.web.config.WebProperties;
import cn.nextdev.lightboot.web.http.HttpStatusCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DatabaseExceptionHandler 切片测试：验证数据库相关异常被正确映射到 409 / 400 / 500。
 *
 * <p>使用 MockMvc standalone 模式，显式挂载测试 controller 与 DatabaseExceptionHandler，
 * 不启动完整 Spring 上下文。重点验证 Result.code → HTTP 状态码的解析：
 * <ul>
 *   <li>DuplicateKeyException → {@link ResultCode#CONFLICT}(40900) → HTTP 409</li>
 *   <li>DataIntegrityViolationException → {@link ResultCode#BAD_REQUEST}(40000) → HTTP 400</li>
 *   <li>通用 DataAccessException → -1 → HTTP 500</li>
 * </ul>
 */
class DatabaseExceptionHandlerTest {

    private MockMvc mockMvc;

    /**
     * 测试用 controller，按请求路径抛出不同的数据库异常。
     */
    @RestController
    static class TestController {
        @GetMapping("/api/db/duplicate-key")
        public void throwDuplicateKey() {
            throw new DuplicateKeyException("唯一索引冲突");
        }

        @GetMapping("/api/db/integrity")
        public void throwIntegrity() {
            throw new DataIntegrityViolationException("外键约束违反");
        }

        @GetMapping("/api/db/generic")
        public void throwGeneric() {
            throw new DataAccessException("连接失败") {
            };
        }
    }

    @BeforeEach
    void setUp() {
        WebProperties props = new WebProperties();
        HttpStatusCodeResolver resolver = new HttpStatusCodeResolver(props);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new DatabaseExceptionHandler(resolver))
                .build();
    }

    /**
     * 唯一约束冲突映射为 HTTP 409，业务码 40900。
     */
    @Test
    void duplicateKeyMapsTo409Conflict() throws Exception {
        mockMvc.perform(get("/api/db/duplicate-key"))
                .andExpect(status().isConflict()) // CONFLICT 40900 → HTTP 409
                .andExpect(jsonPath("$.code").value(ResultCode.CONFLICT.getCode()))
                .andExpect(jsonPath("$.message").value("数据已存在，唯一约束冲突"));
    }

    /**
     * 数据完整性违反映射为 HTTP 400，业务码 40000。
     */
    @Test
    void dataIntegrityViolationMapsTo400BadRequest() throws Exception {
        mockMvc.perform(get("/api/db/integrity"))
                .andExpect(status().isBadRequest()) // BAD_REQUEST 40000 → HTTP 400
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("数据完整性约束违反，操作失败"));
    }

    /**
     * 通用数据访问异常映射为 HTTP 500，业务码 -1。
     */
    @Test
    void genericDataAccessExceptionMapsTo500() throws Exception {
        mockMvc.perform(get("/api/db/generic"))
                .andExpect(status().is5xxServerError()) // -1 → HTTP 500
                .andExpect(jsonPath("$.code").value(-1L))
                .andExpect(jsonPath("$.message").value("数据库操作异常"));
    }
}
