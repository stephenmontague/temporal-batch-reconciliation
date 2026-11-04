package com.example.batchprocessing.core.dto;

import java.util.Map;

public record ReportSummary(String reportPath, Map<String, Integer> rowsByState, Map<String, Integer> failuresByErrorCode) {
}
