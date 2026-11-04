package com.example.recon.view;

import com.example.recon.cli.Command;
import java.io.PrintStream;

public final class UsagePrinter {

    public static void print(PrintStream out) {
        out.println("Usage: java -jar target/recon-poc.jar <command> [arguments]");
        out.println();
        for (Command c : Command.values()) {
            out.println("  " + (c.commandName() + " " + c.arguments()).trim());
            out.println("      " + c.description());
        }
        out.println();
        out.println("Any setting in application.yml can be overridden, e.g. --recon.db=/tmp/recon.db");
    }

    private UsagePrinter() {}
}
