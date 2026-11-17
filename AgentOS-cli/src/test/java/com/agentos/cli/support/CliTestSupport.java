package com.agentos.cli.support;

import static org.mockito.Mockito.mock;

import com.agentos.cli.AgentOSCli;
import com.agentos.core.turn.AgentService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.function.Function;
import picocli.CommandLine;

/** CLI 行为测试的最小共享夹具：临时目录、输出捕获和可替换运行上下文。 */
public final class CliTestSupport {
    private CliTestSupport() {}

    public static AgentService service() {
        return mock(AgentService.class);
    }

    public static CapturedCommand command(
            Path directory,
            com.agentos.cli.ContextLauncher launcher,
            Function<String, String> env) {
        StringWriter output = new StringWriter();
        CommandLine line = AgentOSCli.commandLine(launcher, directory, env);
        line.setOut(new PrintWriter(output, true));
        line.setErr(new PrintWriter(output, true));
        return new CapturedCommand(line, output);
    }

    public record CapturedCommand(CommandLine line, StringWriter output) {}
}
