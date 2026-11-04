package com.example.recon.cli;

import java.util.List;
import org.springframework.boot.ApplicationArguments;

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

    public String option(String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(values.size() - 1);
    }

    public int intOption(String name, int fallback) {
        String v = option(name, null);
        return v == null ? fallback : Integer.parseInt(v);
    }

    public long longOption(String name, long fallback) {
        String v = option(name, null);
        return v == null ? fallback : Long.parseLong(v);
    }

    public double doubleOption(String name, double fallback) {
        String v = option(name, null);
        return v == null ? fallback : Double.parseDouble(v);
    }

    public boolean flag(String name) {
        return args.containsOption(name);
    }
}
