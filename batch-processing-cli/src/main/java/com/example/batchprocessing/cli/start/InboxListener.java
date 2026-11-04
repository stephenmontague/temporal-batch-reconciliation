package com.example.batchprocessing.cli.start;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.batchprocessing.core.config.BatchProcessingProperties;

// Stands in for the queue consumer that starts a run when a file lands: polls the inbox and starts one workflow per new .csv file
@Component
@Profile("intake")
public class InboxListener {

    private static final Logger log = LoggerFactory.getLogger(InboxListener.class);
    private static final long POLL_MILLIS = 2000;

    private final FileIntake intake;
    private final BatchProcessingProperties props;

    public InboxListener(FileIntake intake, BatchProcessingProperties props) {
        this.intake = intake;
        this.props = props;
    }

    public void listen(StartSettings settings) throws IOException, InterruptedException {
        Path inbox = props.getInbox();
        Files.createDirectories(inbox);
        Set<Path> seen = new HashSet<>();
        log.info("Watching {} for new .csv files", inbox.toAbsolutePath());

        while (true) {
            List<Path> csvFiles = listCsvFiles(inbox);

            for (Path file : csvFiles) {
                boolean isNewFile = seen.add(file);

                if (isNewFile) {
                    intake.submit(file, settings);
                }
            }

            Thread.sleep(POLL_MILLIS);
        }
    }

    private static List<Path> listCsvFiles(Path inbox) throws IOException {
        try (Stream<Path> entries = Files.list(inbox)) {
            return entries.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
        }
    }
}
