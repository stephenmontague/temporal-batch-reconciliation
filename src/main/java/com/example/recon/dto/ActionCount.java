package com.example.recon.dto;

public record ActionCount(String workflowId, int workflows, int workflowStarts, int childStarts,
        int activities, int activityRetries, int heartbeatsEstimated, int timers, int signalsReceived,
        int signalsSent, int otherActions, int rootHistoryEvents, int maxChildHistoryEvents,
        int totalHistoryEvents, double wallClockSeconds) {

    public int total() {
        return workflowStarts + childStarts + activities + activityRetries + heartbeatsEstimated
                + timers + signalsReceived + signalsSent + otherActions;
    }
}
