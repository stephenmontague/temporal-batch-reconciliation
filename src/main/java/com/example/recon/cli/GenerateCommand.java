package com.example.recon.cli;

import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.recon.config.ReconProperties;
import com.example.recon.service.CsvGenerator;

@Component
@Profile("generate")
public class GenerateCommand implements ApplicationRunner {

    private final ReconProperties props;

    public GenerateCommand(ReconProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        CommandLineArgs args = new CommandLineArgs(arguments);
        int rows = Integer.parseInt(args.requirePositional(0, "row count"));
        Path out = Path.of(args.option("out",
                props.getInbox().resolve("settlement-" + rows + ".csv").toString()));
        CsvGenerator.generate(out, rows,
                args.doubleOption("invalid-rate", 0.001),
                args.longOption("seed", 42));
        System.out.printf("Wrote %,d rows to %s%n", rows, out.toAbsolutePath());
    }
}
