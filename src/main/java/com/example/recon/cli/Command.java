package com.example.recon.cli;

import java.util.Arrays;
import java.util.Optional;

public enum Command {
    GENERATE("generate", false,
            "<rows> [--out=FILE] [--invalid-rate=R] [--seed=N]",
            "Write a fake settlement CSV (default: <inbox>/settlement-<rows>.csv)"),
    WORKER("worker", true, "",
            "Run the Temporal worker (workflow + activities)"),
    VENDOR("vendor", true, "",
            "Run the fake downstream vendor and callback listener"),
    INTAKE("intake", true,
            "[--design=file|chunk] [--batch-size=N] [--max-parallel=N] [--deadline-seconds=N]",
            "Watch the inbox and start one workflow per new file (stand-in for a file-arrival trigger)"),
    START("start", false,
            "<file> [--design=file|chunk] [--batch-size=N] [--max-parallel=N] [--deadline-seconds=N] [--wait]",
            "Start the workflow for one file and optionally wait for the result"),
    COUNT_ACTIONS("count-actions", false,
            "<workflow-id> [--csv] [--label=TEXT]",
            "Estimate Temporal Cloud billable actions for a workflow and all its child workflows");

    private final String commandName;
    private final boolean longRunning;
    private final String arguments;
    private final String description;

    Command(String commandName, boolean longRunning, String arguments, String description) {
        this.commandName = commandName;
        this.longRunning = longRunning;
        this.arguments = arguments;
        this.description = description;
    }

    public static Optional<Command> find(String name) {
        return Arrays.stream(values()).filter(c -> c.commandName.equals(name)).findFirst();
    }

    public String commandName() {
        return commandName;
    }

    public String profile() {
        return commandName;
    }

    public boolean longRunning() {
        return longRunning;
    }

    public String arguments() {
        return arguments;
    }

    public String description() {
        return description;
    }
}
