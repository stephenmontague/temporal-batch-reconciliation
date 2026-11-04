package com.example.recon.dto;

import java.util.Map;

public record ChunkSummary(int timedOut, Map<String, Integer> rowsByState,
        Map<String, Integer> failuresByErrorCode) {
}
