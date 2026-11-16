package com.agentos.channel.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 可独立测试的终端输入输出适配器，不把交互细节带进 core。
 *
 * <p>输入/输出流由调用方传入并管理关闭，本类只读取、打印与 flush。
 * 当前 ChatCommand 仍自行读标准输入，尚未接入本类。
 */
public final class CliChannel {
    private final BufferedReader input;
    private final PrintWriter output;

    public CliChannel(BufferedReader input, PrintWriter output) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
    }

    /**
     * 在当前线程串行回调；message 阻塞时不会继续读下一条。跳过空白行，/exit 或 EOF 回调 exit。
     * IO 异常或回调异常直接传播，exit 不是 finally 清理钩子。
     */
    public void readLoop(Consumer<String> message, Runnable exit) throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("/exit")) {
                exit.run();
                return;
            }
            if (!line.isBlank()) {
                message.accept(line);
            }
        }
        exit.run();
    }

    /** 立即刷新，避免答案停留在缓冲区而让用户以为程序没有响应。 */
    public void print(String text) {
        output.println(text);
        output.flush();
    }
}
