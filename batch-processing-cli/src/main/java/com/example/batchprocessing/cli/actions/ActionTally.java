package com.example.batchprocessing.cli.actions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.ActivityTaskStartedEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;

// Running totals across one workflow tree, following https://docs.temporal.io/evaluate/cloud/actions
class ActionTally {

    // The SDK sends the first heartbeat at once, then at most one per 80% of the heartbeat timeout
    private static final double HEARTBEAT_THROTTLE = 0.8;

    private int workflows;
    private int workflowStarts;
    private int childStarts;
    private int activities;
    private int activityRetries;
    private int heartbeatsEstimated;
    private int timers;
    private int signalsReceived;
    private int signalsSent;
    private int otherActions;
    private int rootHistoryEvents;
    private int maxChildHistoryEvents;
    private int totalHistoryEvents;
    private double wallClockSeconds;

    // Each add* method returns the child workflows this history started, so the caller can visit them next

    List<WorkflowExecution> addRoot(List<HistoryEvent> events) {
        HistoryEvent first = events.get(0);
        HistoryEvent last = events.get(events.size() - 1);
        long elapsedMillis = millisOf(last.getEventTime()) - millisOf(first.getEventTime());

        rootHistoryEvents = events.size();
        wallClockSeconds = elapsedMillis / 1000.0;

        return add(events);
    }

    List<WorkflowExecution> addChild(List<HistoryEvent> events) {
        maxChildHistoryEvents = Math.max(maxChildHistoryEvents, events.size());

        return add(events);
    }

    ActionCount toActionCount(String workflowId) {
        return new ActionCount(
                workflowId,
                workflows,
                workflowStarts,
                childStarts,
                activities,
                activityRetries,
                heartbeatsEstimated,
                timers,
                signalsReceived,
                signalsSent,
                otherActions,
                rootHistoryEvents,
                maxChildHistoryEvents,
                totalHistoryEvents,
                wallClockSeconds);
    }

    private List<WorkflowExecution> add(List<HistoryEvent> events) {
        HistoryScan scan = new HistoryScan();
        workflows++;
        totalHistoryEvents += events.size();

        for (HistoryEvent event : events) {
            countEvent(event, scan);
        }

        // Local activities bill once per workflow task that recorded markers
        otherActions += scan.localActivityTasks.size();

        return scan.children;
    }

    private void countEvent(HistoryEvent event, HistoryScan scan) {
        EventType type = event.getEventType();

        switch (type) {
            case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED -> workflowStarts++;
            case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED -> childStarts++;
            case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED -> scan.children.add(childExecutionOf(event));
            case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> onActivityScheduled(event, scan);
            case EVENT_TYPE_ACTIVITY_TASK_STARTED -> onActivityStarted(event, scan);
            case EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
                    EVENT_TYPE_ACTIVITY_TASK_FAILED,
                    EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
                    EVENT_TYPE_ACTIVITY_TASK_CANCELED -> onActivityClosed(event, scan);
            case EVENT_TYPE_TIMER_STARTED -> timers++;
            case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED -> signalsReceived++;
            case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED -> signalsSent++;
            case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES, EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED -> otherActions++;
            case EVENT_TYPE_MARKER_RECORDED -> scan.localActivityTasks.add(workflowTaskOf(event));
            default -> {
                // not billable
            }
        }
    }

    private void onActivityScheduled(HistoryEvent scheduled, HistoryScan scan) {
        Duration heartbeatTimeout = scheduled.getActivityTaskScheduledEventAttributes().getHeartbeatTimeout();
        activities++;
        scan.heartbeatTimeoutMillis.put(scheduled.getEventId(), millisOf(heartbeatTimeout));
    }

    private void onActivityStarted(HistoryEvent started, HistoryScan scan) {
        ActivityTaskStartedEventAttributes attributes = started.getActivityTaskStartedEventAttributes();
        int retries = Math.max(0, attributes.getAttempt() - 1);
        activityRetries += retries;
        scan.startedAtMillis.put(attributes.getScheduledEventId(), millisOf(started.getEventTime()));
    }

    // Heartbeats aren't in history, so estimate them from how long the activity ran
    private void onActivityClosed(HistoryEvent closed, HistoryScan scan) {
        long scheduledId = scheduledEventIdOf(closed);
        long heartbeatTimeoutMillis = scan.heartbeatTimeoutMillis.getOrDefault(scheduledId, 0L);
        Long startedAtMillis = scan.startedAtMillis.get(scheduledId);
        boolean heartbeats = heartbeatTimeoutMillis > 0 && startedAtMillis != null;

        if (!heartbeats) {
            return;
        }

        long ranMillis = Math.max(0, millisOf(closed.getEventTime()) - startedAtMillis);
        double throttledIntervalMillis = heartbeatTimeoutMillis * HEARTBEAT_THROTTLE;
        int throttledHeartbeats = (int) (ranMillis / throttledIntervalMillis);
        heartbeatsEstimated += 1 + throttledHeartbeats;
    }

    private static long scheduledEventIdOf(HistoryEvent closed) {
        EventType type = closed.getEventType();

        return switch (type) {
            case EVENT_TYPE_ACTIVITY_TASK_COMPLETED -> closed.getActivityTaskCompletedEventAttributes().getScheduledEventId();
            case EVENT_TYPE_ACTIVITY_TASK_FAILED -> closed.getActivityTaskFailedEventAttributes().getScheduledEventId();
            case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT -> closed.getActivityTaskTimedOutEventAttributes().getScheduledEventId();
            case EVENT_TYPE_ACTIVITY_TASK_CANCELED -> closed.getActivityTaskCanceledEventAttributes().getScheduledEventId();
            default -> throw new IllegalArgumentException("Not an activity close event: " + type);
        };
    }

    private static WorkflowExecution childExecutionOf(HistoryEvent event) {
        return event.getChildWorkflowExecutionStartedEventAttributes().getWorkflowExecution();
    }

    private static long workflowTaskOf(HistoryEvent marker) {
        return marker.getMarkerRecordedEventAttributes().getWorkflowTaskCompletedEventId();
    }

    private static long millisOf(Timestamp timestamp) {
        return timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1_000_000;
    }

    private static long millisOf(Duration duration) {
        return duration.getSeconds() * 1000 + duration.getNanos() / 1_000_000;
    }

    // What one history needs to remember while it's being read
    private static final class HistoryScan {

        final List<WorkflowExecution> children = new ArrayList<>();
        final Map<Long, Long> heartbeatTimeoutMillis = new HashMap<>();
        final Map<Long, Long> startedAtMillis = new HashMap<>();
        final Set<Long> localActivityTasks = new HashSet<>();
    }
}
