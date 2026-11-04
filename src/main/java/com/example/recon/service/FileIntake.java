package com.example.recon.service;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.recon.design.chunk.workflow.ChunkedFileWorkflow;
import com.example.recon.design.file.workflow.ReconciliationFileWorkflow;
import com.example.recon.dto.FileInput;
import com.example.recon.dto.StartSettings;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

public class FileIntake {

    private static final Logger log = LoggerFactory.getLogger(FileIntake.class);

    private final WorkflowClient client;
    private final String fileTaskQueue;
    private final String chunkTaskQueue;

    public FileIntake(WorkflowClient client, String fileTaskQueue, String chunkTaskQueue) {
        this.client = client;
        this.fileTaskQueue = fileTaskQueue;
        this.chunkTaskQueue = chunkTaskQueue;
    }

    public static String fileIdOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
    }

    public static String workflowIdOf(String fileId) {
        return "recon-" + fileId;
    }

    public String submit(Path file, StartSettings settings) {
        String fileId = fileIdOf(file);
        String workflowId = workflowIdOf(fileId);

        FileInput input = new FileInput(file.toAbsolutePath().toString(), fileId, settings.batchSize(),
                settings.maxParallel(), settings.deadlineSeconds());

        boolean chunkDesign = StartSettings.CHUNK_DESIGN.equals(settings.design());

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(chunkDesign ? chunkTaskQueue : fileTaskQueue)
                .build();

        try {
            WorkflowExecution exec = chunkDesign
                    ? WorkflowClient.start(client.newWorkflowStub(ChunkedFileWorkflow.class, options)::reconcile, input)
                    : WorkflowClient.start(client.newWorkflowStub(ReconciliationFileWorkflow.class, options)::reconcile,
                            input);

            log.info("Started {} ({} design, run {}) for {}", workflowId, settings.design(), exec.getRunId(), file);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("{} is already running; duplicate delivery ignored", workflowId);
        }

        return workflowId;
    }
}
