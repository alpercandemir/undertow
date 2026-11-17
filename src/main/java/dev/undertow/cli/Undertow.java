package dev.undertow.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "undertow",
        mixinStandardHelpOptions = true,
        version = "Undertow 0.1.0-alpha.1",
        description = "Find reproducible behavior failures and verify approved Java contracts")
public final class Undertow implements Runnable {
    @Spec private CommandSpec command;

    @Override
    public void run() {
        command.commandLine().usage(command.commandLine().getOut());
    }

    public static void main(String[] args) {
        UndertowApplication.main(args);
    }
}
