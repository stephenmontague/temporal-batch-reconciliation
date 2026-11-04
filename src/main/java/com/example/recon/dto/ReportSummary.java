package com.example.recon.dto;

import java.util.Map;

public record ReportSummary(String reportPath, Map<String, Integer> rowsByState,
        Map<String, Integer> failuresByErrorCode) {
}
