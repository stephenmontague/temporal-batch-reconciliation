package com.example.batchprocessing.core.testsupport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;

// How many times each activity type was scheduled, across a workflow and every child under it
public final class ActivityTypeCounts {

    public static Map<String, Integer> of(WorkflowClient client, String rootWorkflowId) {
        Map<String, Integer> counts = new TreeMap<>();
        Deque<WorkflowExecution> toVisit = new ArrayDeque<>();
        WorkflowExecution root = WorkflowExecution.newBuilder().setWorkflowId(rootWorkflowId).build();
        toVisit.add(root);

        while (!toVisit.isEmpty()) {
            WorkflowExecution execution = toVisit.poll();
            List<HistoryEvent> events = client.fetchHistory(execution.getWorkflowId(), execution.getRunId()).getEvents();

            for (HistoryEvent event : events) {
                EventType type = event.getEventType();

                if (type == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
                    String activityType = event.getActivityTaskScheduledEventAttributes().getActivityType().getName();
                    counts.merge(activityType, 1, Integer::sum);
                }

                if (type == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED) {
                    toVisit.add(event.getChildWorkflowExecutionStartedEventAttributes().getWorkflowExecution());
                }
            }
        }

        return counts;
    }

    private ActivityTypeCounts() {}
}
