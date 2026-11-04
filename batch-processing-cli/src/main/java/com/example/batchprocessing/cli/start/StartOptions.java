package com.example.batchprocessing.cli.start;

import java.util.Arrays;
import java.util.List;

import com.example.batchprocessing.cli.config.StartProperties;
import com.example.batchprocessing.cli.launch.CommandLineArgs;

// The start and intake options: from the command line, falling back to batch-processing.start.* settings
public final class StartOptions {

    public static StartSettings resolve(CommandLineArgs args, StartProperties defaults) {
        int batchSize = args.intOption("batch-size", defaults.getBatchSize());
        int maxParallel = args.intOption("max-parallel", defaults.getMaxParallel());
        long deadlineSeconds = args.longOption("deadline-seconds", defaults.getDeadlineSeconds());
        List<String> retryCodes = retryCodesOf(args, defaults);

        return new StartSettings(batchSize, maxParallel, deadlineSeconds, retryCodes);
    }

    // --retry-codes=TIMEOUT,ACCOUNT_LOCKED retries both; --retry-codes= (empty) retries nothing
    private static List<String> retryCodesOf(CommandLineArgs args, StartProperties defaults) {
        String option = args.option("retry-codes", null);

        if (option == null) {
            return List.copyOf(defaults.getRetryCodes());
        }

        return Arrays.stream(option.split(",")).map(String::trim).filter(code -> !code.isEmpty()).toList();
    }

    private StartOptions() {}
}
