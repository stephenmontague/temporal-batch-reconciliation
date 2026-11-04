package com.example.batchprocessing.cli.actions;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;

// Estimates Temporal Cloud billable actions for a workflow and every child workflow under it, by reading their histories.
public class ActionCounter {

    private final WorkflowClient client;

    public ActionCounter(WorkflowClient client) {
        this.client = client;
    }

    public ActionCount count(String workflowId) {
        ActionTally tally = new ActionTally();
        List<HistoryEvent> rootEvents = client.fetchHistory(workflowId).getEvents();
        List<WorkflowExecution> rootChildren = tally.addRoot(rootEvents);
        Deque<WorkflowExecution> toVisit = new ArrayDeque<>(rootChildren);

        while (!toVisit.isEmpty()) {
            WorkflowExecution child = toVisit.poll();
            List<HistoryEvent> childEvents = client.fetchHistory(child.getWorkflowId(), child.getRunId()).getEvents();
            List<WorkflowExecution> grandchildren = tally.addChild(childEvents);
            toVisit.addAll(grandchildren);
        }

        return tally.toActionCount(workflowId);
    }
}
