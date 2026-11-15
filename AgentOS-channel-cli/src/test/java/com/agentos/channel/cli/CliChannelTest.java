package com.agentos.channel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliChannelTest {
    @Test
    void skipsBlankLinesAndStopsOnExitOrEof() throws Exception {
        StringWriter output = new StringWriter();
        List<String> messages = new ArrayList<>();
        List<String> exits = new ArrayList<>();
        new CliChannel(
                        new BufferedReader(new StringReader("\nhello\n/exit\nignored\n")),
                        new PrintWriter(output))
                .readLoop(messages::add, () -> exits.add("exit"));
        assertThat(messages).containsExactly("hello");
        assertThat(exits).containsExactly("exit");
    }

    @Test
    void eofAlsoEndsInteraction() throws Exception {
        List<String> exits = new ArrayList<>();
        new CliChannel(
                        new BufferedReader(new StringReader("hello\n")),
                        new PrintWriter(new StringWriter()))
                .readLoop(ignored -> {}, () -> exits.add("exit"));
        assertThat(exits).containsExactly("exit");
    }
}
