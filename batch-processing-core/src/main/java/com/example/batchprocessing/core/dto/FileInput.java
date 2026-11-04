package com.example.batchprocessing.core.dto;

import java.util.List;

// What the CLI hands BasicWorkflow. Only references go through Temporal; row data stays in the database.
// retryableErrorCodes: vendor error codes worth sending again (TIMEOUT by default); rows failed with any other code stay FAILED.
public record FileInput(
        String fileRef,
        String fileId,
        int batchSize,
        int maxParallel,
        long deadlineSeconds,
        List<String> retryableErrorCodes) {
}
