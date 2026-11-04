package com.example.batchprocessing.core.vendor;

import java.util.List;

public record FakeVendorSettings(
        double minDelaySeconds,
        double maxDelaySeconds,
        double failureRate,
        List<String> errorCodes,
        long pollMillis,
        int pollBatch,
        long seed) {
}
