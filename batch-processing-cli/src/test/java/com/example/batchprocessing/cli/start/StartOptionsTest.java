package com.example.batchprocessing.cli.start;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.example.batchprocessing.cli.config.StartProperties;
import com.example.batchprocessing.cli.launch.CommandLineArgs;

class StartOptionsTest {

    @Test
    void fallsBackToConfiguredDefaults() {
        StartProperties defaults = new StartProperties();
        defaults.setMaxParallel(4);

        StartSettings settings = StartOptions.resolve(parse("--deadline-seconds=120"), defaults);

        assertEquals(1000, settings.batchSize());
        assertEquals(4, settings.maxParallel());
        assertEquals(120, settings.deadlineSeconds());
        assertEquals(List.of("TIMEOUT"), settings.retryableErrorCodes());
    }

    @Test
    void retryCodesComeFromACommaSeparatedList() {
        StartSettings settings = StartOptions.resolve(parse("--retry-codes=TIMEOUT, ACCOUNT_LOCKED"), new StartProperties());

        assertEquals(List.of("TIMEOUT", "ACCOUNT_LOCKED"), settings.retryableErrorCodes());
    }

    @Test
    void emptyRetryCodesRetryNothing() {
        StartSettings settings = StartOptions.resolve(parse("--retry-codes="), new StartProperties());

        assertEquals(List.of(), settings.retryableErrorCodes());
    }

    @Test
    void workflowIdComesFromTheFileName() {
        String fileId = FileIntake.fileIdOf(Path.of("data/inbox/settlement-100000.csv"));

        assertEquals("settlement-100000", fileId);
        assertEquals("basic-workflow-settlement-100000", FileIntake.workflowIdOf(fileId));
    }

    @Test
    void fileAlreadyNamedForTheWorkflowIsNotPrefixedTwice() {
        assertEquals("basic-workflow-settlement-100000", FileIntake.workflowIdOf("basic-workflow-settlement-100000"));
    }

    private static CommandLineArgs parse(String... args) {
        return new CommandLineArgs(new DefaultApplicationArguments(args));
    }
}
