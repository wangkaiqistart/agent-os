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

/** 统一信封与错误码映射的验收测试。 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new StubController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("成功响应：code=OK，带 data 和 timestamp")
    void okEnvelope() throws Exception {
        mvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").value("pong"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("业务异常：按错误码映射状态码，消息原样返回")
    void businessError() throws Exception {
        mvc.perform(get("/test/busy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_BUSY"))
                .andExpect(jsonPath("$.message").value("会话正有轮次在执行"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("未知异常：500 + 固定文案，不泄露内部信息")
    void unexpectedError() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("内部错误"));
    }

    @Test
    @DisplayName("参数校验失败：400 + 字段说明")
    void validationError() throws Exception {
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
        mvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Bad Request"));
    }

    @Test
    @DisplayName("方法不支持：405，统一信封")
    void methodNotAllowed() throws Exception {
        mvc.perform(post("/test/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("每个错误码都有 HTTP 状态码映射")
    void everyErrorCodeMapped(ErrorCode code) {
        assertThat(GlobalExceptionHandler.toHttpStatus(code)).isNotNull();
    }

    record EchoRequest(@NotBlank String text) {}

    @RestController
    static class StubController {

        @GetMapping("/test/ok")
        ApiResponse<String> ok() {
            return ApiResponse.ok("pong");
        }

        @GetMapping("/test/busy")
        ApiResponse<Void> busy() {
            throw new AgentOSException(ErrorCode.SESSION_BUSY, "会话正有轮次在执行");
        }

        @GetMapping("/test/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("数据库密码错误：secret");
        }

        @PostMapping("/test/echo")
        ApiResponse<String> echo(@Valid @RequestBody EchoRequest request) {
            return ApiResponse.ok(request.text());
        }
    }
}
