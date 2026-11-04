package com.example.batchprocessing.cli.start;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.batchprocessing.core.config.BatchProcessingProperties;
import com.example.batchprocessing.core.contract.WorkflowNames;
import com.example.batchprocessing.core.dto.FileInput;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

// Starts one BasicWorkflow for a file. It starts the workflow by type name, so the CLI doesn't depend on the worker's code;
// WorkflowNames is the contract between them.
public class FileIntake {

    private static final String WORKFLOW_ID_PREFIX = "basic-workflow-";

    private static final Logger log = LoggerFactory.getLogger(FileIntake.class);

    private final WorkflowClient client;
    private final BatchProcessingProperties props;

    public FileIntake(WorkflowClient client, BatchProcessingProperties props) {
        this.client = client;
        this.props = props;
    }

    // settlement-100000.csv -> settlement-100000
    public static String fileIdOf(Path file) {
        String name = file.getFileName().toString();

        return name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
    }

    // settlement-100000 -> basic-workflow-settlement-100000 (a file already named basic-workflow-* keeps its name).
    // It's derived from the file, so a duplicate trigger for a file that's already running is rejected by Temporal.
    public static String workflowIdOf(String fileId) {
        boolean alreadyPrefixed = fileId.startsWith(WORKFLOW_ID_PREFIX);

        if (alreadyPrefixed) {
            return fileId;
        }

        return WORKFLOW_ID_PREFIX + fileId;
    }

    public String submit(Path file, StartSettings settings) {
        String fileId = fileIdOf(file);
        String workflowId = workflowIdOf(fileId);
        FileInput input = fileInputOf(file, fileId, settings);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(props.getBasicWorkflowTaskQueue())
                .build();
        WorkflowStub workflow = client.newUntypedWorkflowStub(WorkflowNames.BASIC_WORKFLOW, options);

        try {
            WorkflowExecution execution = workflow.start(input);
            log.info("Started {} (run {}) for {}", workflowId, execution.getRunId(), file);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("{} is already running; duplicate delivery ignored", workflowId);
        }

        return workflowId;
    }

    private static FileInput fileInputOf(Path file, String fileId, StartSettings settings) {
        String fileRef = file.toAbsolutePath().toString();

        return new FileInput(
                fileRef,
                fileId,
                settings.batchSize(),
                settings.maxParallel(),
                settings.deadlineSeconds(),
                settings.retryableErrorCodes());
    }
}
