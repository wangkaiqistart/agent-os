package com.agentos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一信封与错误码映射的验收测试。
 *
 * <p>这里没有启动完整应用，而是用 MockMvc 加一个临时 StubController，
 * 专门验证异常处理器的输入和输出。这样测试快，也不会依赖数据库或真实网络。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // standaloneSetup 只装配测试需要的 Controller 和 Advice。
        mvc =
                MockMvcBuilders.standaloneSetup(new StubController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("成功响应：code=OK，带 data 和 timestamp")
    void okEnvelope() throws Exception {
        // GET /test/ok 返回成功信封，并检查 JSON 的关键字段。
        mvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").value("pong"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("业务异常：按错误码映射状态码，消息原样返回")
    void businessError() throws Exception {
        // Controller 主动抛出 SESSION_BUSY，期望被转换成 HTTP 409。
        mvc.perform(get("/test/busy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_BUSY"))
                .andExpect(jsonPath("$.message").value("会话正有轮次在执行"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("未知异常：500 + 固定文案，不泄露内部信息")
    void unexpectedError() throws Exception {
        // StubController 内部故意抛出带 secret 的异常，验证响应不会泄露原始 message。
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("内部错误"));
    }

    @Test
    @DisplayName("参数校验失败：400 + 字段说明")
    void validationError() throws Exception {
        // @NotBlank 拒绝空 text，异常由 Spring MVC 转交给 GlobalExceptionHandler。
        mvc.perform(
                        post("/test/echo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(
                        jsonPath("$.message").value(org.hamcrest.Matchers.containsString("text")));
    }

    @Test
    @DisplayName("JSON 格式错误：400，不回显解析器内部信息")
    void malformedJson() throws Exception {
        // 发送非法 JSON，验证客户端只能看到标准 Bad Request。
        mvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Bad Request"));
    }

    @Test
    @DisplayName("方法不支持：405，统一信封")
    void methodNotAllowed() throws Exception {
        // /test/ok 只有 GET，使用 POST 应得到 405，同时仍使用统一响应格式。
        mvc.perform(post("/test/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("每个错误码都有 HTTP 状态码映射")
    void everyErrorCodeMapped(ErrorCode code) {
        // 枚举中的每一个值都会跑一次，防止新增错误码后忘记补映射。
        assertThat(GlobalExceptionHandler.toHttpStatus(code)).isNotNull();
    }

    // 测试专用请求 DTO：text 不能为空，用来触发参数校验异常。
    record EchoRequest(@NotBlank String text) {}

    @RestController
    static class StubController {

        @GetMapping("/test/ok")
        ApiResponse<String> ok() {
            // 模拟正常 Controller 返回值。
            return ApiResponse.ok("pong");
        }

        @GetMapping("/test/busy")
        ApiResponse<Void> busy() {
            // 模拟业务层发现同一会话正在执行。
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "会话正有轮次在执行");
        }

        @GetMapping("/test/boom")
        ApiResponse<Void> boom() {
            // 模拟未知异常；message 故意包含敏感样例，测试响应脱敏。
            throw new IllegalStateException("数据库密码错误：secret");
        }

        @PostMapping("/test/echo")
        ApiResponse<String> echo(@Valid @RequestBody EchoRequest request) {
            // @Valid 让 Spring 在进入方法前先校验请求体。
            return ApiResponse.ok(request.text());
        }
    }
}
