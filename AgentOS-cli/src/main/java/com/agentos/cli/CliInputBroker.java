package com.agentos.cli;

/** channel终端broker的CLI入口；两者共享同一reader与generation。 */
public final class CliInputBroker extends com.agentos.channel.cli.TerminalInputBroker {
    public CliInputBroker(java.io.Reader input, java.io.PrintWriter output) {
        super(input, output);
    }
}
