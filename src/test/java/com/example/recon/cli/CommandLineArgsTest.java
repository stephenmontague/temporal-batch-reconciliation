package com.example.recon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.recon.config.StartProperties;
import com.example.recon.dto.StartSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class CommandLineArgsTest {

    private static CommandLineArgs parse(String... args) {
        return new CommandLineArgs(new DefaultApplicationArguments(args));
    }

    @Test
    void readsPositionalOptionsAndFlags() {
        CommandLineArgs args = parse("data/inbox/f.csv", "--batch-size=500", "--wait");

        assertEquals("data/inbox/f.csv", args.requirePositional(0, "file path"));
        assertEquals(500, args.intOption("batch-size", 1000));
        assertEquals(7200, args.longOption("deadline-seconds", 7200));
        assertTrue(args.flag("wait"));
        assertFalse(args.flag("verbose"));
    }

    @Test
    void missingPositionalIsAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("--wait").requirePositional(0, "file path"));
        assertEquals("Missing file path", e.getMessage());
    }

    @Test
    void startOptionsFallBackToConfiguredDefaults() {
        StartProperties defaults = new StartProperties();
        defaults.setMaxParallel(4);

        StartSettings s = StartOptions.resolve(parse("--deadline-seconds=120"), defaults);

        assertEquals(1000, s.batchSize());
        assertEquals(4, s.maxParallel());
        assertEquals(120, s.deadlineSeconds());
    }

    @Test
    void everyCommandHasItsOwnProfile() {
        for (Command c : Command.values()) {
            assertEquals(c, Command.find(c.commandName()).orElseThrow());
            assertEquals(c.commandName(), c.profile());
        }
        assertTrue(Command.find("nope").isEmpty());
    }
}
