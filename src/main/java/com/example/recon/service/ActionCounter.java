package com.example.recon.service;

import java.util.ArrayDeque;
import java.util.Deque;

import com.example.recon.dto.ActionCount;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;

public class ActionCounter {

    private final WorkflowClient client;

    public ActionCounter(WorkflowClient client) {
        this.client = client;
    }

    public ActionCount count(String workflowId) {
        ActionTally tally = new ActionTally();
        Deque<WorkflowExecution> children = new ArrayDeque<>(
                tally.add(client.fetchHistory(workflowId).getEvents(), true));
        while (!children.isEmpty()) {
            WorkflowExecution child = children.poll();
            children.addAll(tally.add(
                    client.fetchHistory(child.getWorkflowId(), child.getRunId()).getEvents(), false));
        }
        return new ActionCount(workflowId, tally.workflows, tally.workflowStarts, tally.childStarts,
                tally.activities, tally.activityRetries, tally.heartbeatsEstimated, tally.timers,
                tally.signalsReceived, tally.signalsSent, tally.otherActions, tally.rootHistoryEvents,
                tally.maxChildHistoryEvents, tally.totalHistoryEvents, tally.wallClockSeconds);
    }
}
