package com.example.batchprocessing.cli.launch;

import java.util.List;

import org.springframework.boot.ApplicationArguments;

// Positional arguments, --name=value options, and --flags, with fallbacks
public final class CommandLineArgs {

    private final ApplicationArguments args;

    public CommandLineArgs(ApplicationArguments args) {
        this.args = args;
    }

    public String requirePositional(int index, String what) {
        List<String> positional = args.getNonOptionArgs();

        if (index >= positional.size()) {
            throw new IllegalArgumentException("Missing " + what);
        }

        return positional.get(index);
    }

    // The last value wins when an option is given twice
    public String option(String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        boolean missing = values == null || values.isEmpty();

        if (missing) {
            return fallback;
        }

        return values.get(values.size() - 1);
    }

    public int intOption(String name, int fallback) {
        String value = option(name, null);

        return value == null ? fallback : Integer.parseInt(value);
    }

    public long longOption(String name, long fallback) {
        String value = option(name, null);

        return value == null ? fallback : Long.parseLong(value);
    }

    public double doubleOption(String name, double fallback) {
        String value = option(name, null);

        return value == null ? fallback : Double.parseDouble(value);
    }

    public boolean flag(String name) {
        return args.containsOption(name);
    }
}
