package com.example.recon.cli;

import com.example.recon.config.ReconProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class WorkerStatus {

    private final ReconProperties props;

    public WorkerStatus(ReconProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        System.out.printf("File design worker polling task queue '%s'%n", props.getFileTaskQueue());
        System.out.printf("Chunk design worker polling task queue '%s'%n", props.getChunkTaskQueue());
        System.out.printf("Database: %s%n", props.getDb().toAbsolutePath());
    }
}
