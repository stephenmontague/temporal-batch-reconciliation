package com.example.batchprocessing.basicworkflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import com.example.batchprocessing.basicworkflow.config.ReconciliationActivitiesConfig;
import com.example.batchprocessing.core.config.BatchProcessingProperties;

// Temporal worker: one BasicWorkflow reconciles the whole file.
// What it registers (BasicWorkflowImpl + the reconciliationActivities bean) is listed in application.yml.
@SpringBootApplication
@Import(ReconciliationActivitiesConfig.class)
public class BasicWorkflowWorkerApplication {

    private final BatchProcessingProperties props;
    private final int metricsPort;

    // The app also runs a small web server, only to serve SDK metrics (port in application.yml)
    public BasicWorkflowWorkerApplication(BatchProcessingProperties props, @Value("${server.port}") int metricsPort) {
        this.props = props;
        this.metricsPort = metricsPort;
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BasicWorkflowWorkerApplication.class);
        app.setKeepAlive(true);
        app.run(args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        System.out.printf("Basic-workflow worker polling task queue '%s'%n", props.getBasicWorkflowTaskQueue());
        System.out.printf("Database: %s%n", props.getDb().toAbsolutePath());
        System.out.printf("SDK metrics: http://localhost:%d/actuator/prometheus%n", metricsPort);
    }
}
