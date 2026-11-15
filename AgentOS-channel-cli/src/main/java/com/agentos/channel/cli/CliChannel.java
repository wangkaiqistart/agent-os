package com.agentos.channel.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Consumer;

/** 只负责 CLI 输入输出，不把交互细节带进 core。 */
public final class CliChannel {
    private final BufferedReader input;
    private final PrintWriter output;

    public CliChannel(BufferedReader input, PrintWriter output) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
    }

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

    public void print(String text) {
        output.println(text);
        output.flush();
    }
}
