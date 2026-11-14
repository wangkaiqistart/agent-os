package com.agentos.provider.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ProviderContextRunners;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.MimeTypeUtils;

/**
 * 图片输入的真实冒烟测试：一张纯红色图片，问模型是什么颜色（spec US6-5、SC-017）。
 *
 * <p>只在 {@code mvn verify -Pllm} 且设置了对应的 API key 时运行，两个连接各一个用例，互不影响。
 * 密钥、地址、模型都从环境变量取，不写进代码，也不打印。独立成一个类，不和流式冒烟测试混在一起。
 */
@Tag("llm")
class ImageSmokeTest {

    private static final String QUESTION = "这张图片是什么颜色？请只回答颜色名。";

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTOS_LLM_API_KEY", matches = ".+")
    @DisplayName("primary：回答包含红色")
    void primaryRecognizesRed() {
        runner("primary", "openai", "${AGENTOS_LLM_BASE_URL:}", "${AGENTOS_LLM_API_KEY:}", "120s")
                .run(context -> {
                    // 推理模型：不发 temperature；上限给足，避免推理吃光额度。
                    Profile profile =
                            new Profile(
                                    "primary",
                                    System.getenv("AGENTOS_LLM_MODEL"),
                                    "low",
                                    2048,
                                    null);

                    ProviderResult result =
                            context.getBean(ProviderService.class).call(request(profile));

                    assertAnswersRed(result);
                });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTOS_DEEPSEEK_API_KEY", matches = ".+")
    @DisplayName("deepseek：回答包含红色")
    void deepseekRecognizesRed() {
        runner(
                        "deepseek",
                        "deepseek",
                        "${AGENTOS_DEEPSEEK_BASE_URL:https://api.deepseek.com}",
                        "${AGENTOS_DEEPSEEK_API_KEY:}",
                        "60s")
                .run(context -> {
                    Profile profile =
                            new Profile(
                                    "deepseek",
                                    System.getenv("AGENTOS_DEEPSEEK_MODEL"),
                                    null,
                                    1024,
                                    null);

                    ProviderResult result =
                            context.getBean(ProviderService.class).call(request(profile));

                    assertAnswersRed(result);
                });
    }

    private static void assertAnswersRed(ProviderResult result) {
        String answer = result.response().getResults().getFirst().getOutput().getText();
        assertThat(answer).isNotBlank();
        // 中文问法，模型可能答"红色"也可能答 red，两种都算对。
        assertThat(answer.toLowerCase(Locale.ROOT)).containsAnyOf("红", "red");
        LlmCallRecord record = result.calls().getFirst();
        assertThat(record.success()).isTrue();
        assertThat(record.mode()).isEqualTo(CallMode.CALL);
        assertThat(record.imageCount()).isEqualTo(1);
    }

    private static ProviderRequest request(Profile profile) {
        UserMessage message =
                UserMessage.builder()
                        .text(QUESTION)
                        .media(
                                Media.builder()
                                        .mimeType(MimeTypeUtils.IMAGE_PNG)
                                        .data(solidRedPng())
                                        .build())
                        .build();
        return new ProviderRequest(profile, List.of(message), null, null, null, null);
    }

    /** 64x64 的纯红色 PNG。尺寸太小有的服务会拒绝，64 像素足够识别。 */
    private static byte[] solidRedPng() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 64, 64);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", output);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return output.toByteArray();
    }

    private static ApplicationContextRunner runner(
            String name, String type, String baseUrl, String apiKey, String timeout) {
        String prefix = "agentos.providers." + name + ".";
        return ProviderContextRunners.base()
                .withPropertyValues(
                        "agentos.default-provider=" + name,
                        prefix + "type=" + type,
                        prefix + "base-url=" + baseUrl,
                        prefix + "api-key=" + apiKey,
                        prefix + "timeout=" + timeout,
                        prefix + "retry-min-remaining=15s");
    }
}
