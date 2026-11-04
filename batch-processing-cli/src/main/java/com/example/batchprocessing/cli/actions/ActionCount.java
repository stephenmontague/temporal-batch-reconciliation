package com.example.batchprocessing.cli.actions;

// Estimated Temporal Cloud billable actions for one workflow and all its child workflows.
public record ActionCount(
        String workflowId,
        int workflows,
        int workflowStarts,
        int childStarts,
        int activities,
        int activityRetries,
        int heartbeatsEstimated,
        int timers,
        int signalsReceived,
        int signalsSent,
        int otherActions,
        int rootHistoryEvents,
        int maxChildHistoryEvents,
        int totalHistoryEvents,
        double wallClockSeconds) {

    public int total() {
        int workflowActions = workflowStarts + childStarts;
        int activityActions = activities + activityRetries + heartbeatsEstimated;
        int eventActions = timers + signalsReceived + signalsSent + otherActions;

        return workflowActions + activityActions + eventActions;
    }
}
