package com.example.batchprocessing.cli.command;

import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.batchprocessing.cli.launch.CommandLineArgs;
import com.example.batchprocessing.core.config.BatchProcessingProperties;
import com.example.batchprocessing.core.csv.CsvGenerator;

// Writes a fake settlement CSV: <inbox>/settlement-<rows>.csv, or --out=FILE. Transaction IDs start with the file name,
// and the store skips IDs it has already seen, so a new file name gives a file that runs as new.
@Component
@Profile("generate")
public class GenerateCommand implements ApplicationRunner {

    private static final double DEFAULT_INVALID_RATE = 0.001;
    private static final long DEFAULT_SEED = 42;

    private final BatchProcessingProperties props;

    public GenerateCommand(BatchProcessingProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        CommandLineArgs args = new CommandLineArgs(arguments);
        int rows = Integer.parseInt(args.requirePositional(0, "row count"));
        Path defaultOut = props.getInbox().resolve("settlement-" + rows + ".csv");
        Path out = Path.of(args.option("out", defaultOut.toString()));
        double invalidRate = args.doubleOption("invalid-rate", DEFAULT_INVALID_RATE);
        long seed = args.longOption("seed", DEFAULT_SEED);

        CsvGenerator.generate(out, rows, invalidRate, seed);

        System.out.printf("Wrote %,d rows to %s%n", rows, out.toAbsolutePath());
    }
}
