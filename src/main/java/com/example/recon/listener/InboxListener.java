package com.example.recon.listener;

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

import com.example.recon.config.ReconProperties;
import com.example.recon.dto.StartSettings;
import com.example.recon.service.FileIntake;

@Component
@Profile("intake")
public class InboxListener {

    private static final Logger log = LoggerFactory.getLogger(InboxListener.class);
    private static final long POLL_MILLIS = 2000;

    private final FileIntake intake;
    private final ReconProperties props;

    public InboxListener(FileIntake intake, ReconProperties props) {
        this.intake = intake;
        this.props = props;
    }

    public void listen(StartSettings settings) throws IOException, InterruptedException {
        Path inbox = props.getInbox();
        Files.createDirectories(inbox);
        Set<Path> seen = new HashSet<>();
        log.info("Watching {} for new .csv files", inbox.toAbsolutePath());
        while (true) {
            List<Path> files;
            try (Stream<Path> s = Files.list(inbox)) {
                files = s.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
            }
            for (Path f : files) {
                if (seen.add(f)) {
                    intake.submit(f, settings);
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
    }
}
