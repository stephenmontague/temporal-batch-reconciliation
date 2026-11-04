package com.example.batchprocessing.cli.start;

import java.util.List;

public record StartSettings(int batchSize, int maxParallel, long deadlineSeconds, List<String> retryableErrorCodes) {
}
