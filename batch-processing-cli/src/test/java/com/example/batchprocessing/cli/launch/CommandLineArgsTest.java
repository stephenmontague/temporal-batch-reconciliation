package com.example.batchprocessing.cli.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class CommandLineArgsTest {

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
    void lastValueWinsWhenAnOptionRepeats() {
        CommandLineArgs args = parse("--batch-size=500", "--batch-size=2000");

        assertEquals(2000, args.intOption("batch-size", 1000));
    }

    @Test
    void missingPositionalIsAnError() {
        CommandLineArgs args = parse("--wait");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> args.requirePositional(0, "file path"));

        assertEquals("Missing file path", error.getMessage());
    }

    @Test
    void everyCommandHasItsOwnProfile() {
        for (Command command : Command.values()) {
            Command found = Command.find(command.commandName()).orElseThrow();

            assertEquals(command, found);
            assertEquals(command.commandName(), command.profile());
        }

        assertTrue(Command.find("nope").isEmpty());
        assertTrue(Command.find("worker").isEmpty(), "workers run from their own jars now");
    }

    private static CommandLineArgs parse(String... args) {
        return new CommandLineArgs(new DefaultApplicationArguments(args));
    }
}
