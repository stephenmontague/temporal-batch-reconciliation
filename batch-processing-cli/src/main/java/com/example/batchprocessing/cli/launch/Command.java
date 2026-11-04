package com.example.batchprocessing.cli.launch;

import java.util.Arrays;
import java.util.Optional;

// Each command runs Spring Boot with a profile of the same name, so a process only creates the beans that command needs.
public enum Command {

    GENERATE(
            "generate",
            false,
            "<rows> [--out=FILE] [--invalid-rate=R] [--seed=N]",
            "Write a fake settlement CSV (default: <inbox>/settlement-<rows>.csv)"),

    START(
            "start",
            false,
            "<file> [--batch-size=N] [--max-parallel=N] [--deadline-seconds=N] [--retry-codes=A,B] [--wait]",
            "Start the workflow for one file and optionally wait for the result"),

    INTAKE(
            "intake",
            true,
            "[--batch-size=N] [--max-parallel=N] [--deadline-seconds=N] [--retry-codes=A,B]",
            "Watch the inbox and start one workflow per new file (stand-in for a file-arrival trigger)"),

    COUNT_ACTIONS(
            "count-actions",
            false,
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
