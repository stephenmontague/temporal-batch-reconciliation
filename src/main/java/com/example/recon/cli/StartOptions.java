package com.example.recon.cli;

import com.example.recon.config.StartProperties;
import com.example.recon.dto.StartSettings;

public final class StartOptions {

    public static StartSettings resolve(CommandLineArgs args, StartProperties defaults) {
        return new StartSettings(
                args.intOption("batch-size", defaults.getBatchSize()),
                args.intOption("max-parallel", defaults.getMaxParallel()),
                args.longOption("deadline-seconds", defaults.getDeadlineSeconds()),
                args.option("design", defaults.getDesign()));
    }

    private StartOptions() {}
}
