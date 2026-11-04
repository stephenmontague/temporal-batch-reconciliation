package com.example.recon.dto;

import java.util.Map;

public record ChunkResult(int chunkIndex, int rowsRead, int rowsInvalid, int rowsPending,
        int rowsSubmitted, boolean allAnsweredBeforeDeadline, int rowsTimedOut,
        Map<String, Integer> rowsByState, Map<String, Integer> failuresByErrorCode) {
}
