package com.example.batchprocessing.cli.view;

import java.io.PrintStream;

import com.example.batchprocessing.cli.launch.Command;

public final class UsagePrinter {

    public static void print(PrintStream out) {
        out.println("Usage: java -jar batch-processing-cli/target/batch-processing-cli.jar <command> [arguments]");
        out.println();

        for (Command command : Command.values()) {
            String usageLine = (command.commandName() + " " + command.arguments()).trim();
            out.println("  " + usageLine);
            out.println("      " + command.description());
        }

        out.println();
        out.println("Any setting in application.yml can be overridden, e.g. --batch-processing.inbox=/tmp/inbox");
        out.println("The workers and the vendor are separate jars; see README.md.");
    }

    private UsagePrinter() {}
}
