package com.example.recon.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;

class ActionTally {

    private static final double HEARTBEAT_THROTTLE = 0.8;

    int workflows;
    int workflowStarts;
    int childStarts;
    int activities;
    int activityRetries;
    int heartbeatsEstimated;
    int timers;
    int signalsReceived;
    int signalsSent;
    int otherActions;
    int rootHistoryEvents;
    int maxChildHistoryEvents;
    int totalHistoryEvents;
    double wallClockSeconds;

    List<WorkflowExecution> add(List<HistoryEvent> events, boolean root) {
        workflows++;
        totalHistoryEvents += events.size();
        if (root) {
            rootHistoryEvents = events.size();
            wallClockSeconds = (millis(events.get(events.size() - 1)) - millis(events.get(0))) / 1000.0;
        } else {
            maxChildHistoryEvents = Math.max(maxChildHistoryEvents, events.size());
        }
        List<WorkflowExecution> children = new ArrayList<>();
        Map<Long, Long> heartbeatTimeoutMillis = new HashMap<>();
        Map<Long, Long> startedMillis = new HashMap<>();
        Set<Long> localActivityTasks = new HashSet<>();
        for (HistoryEvent e : events) {
            switch (e.getEventType()) {
                case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED -> workflowStarts++;
                case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED -> childStarts++;
                case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED ->
                        children.add(e.getChildWorkflowExecutionStartedEventAttributes().getWorkflowExecution());
                case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
                    activities++;
                    var timeout = e.getActivityTaskScheduledEventAttributes().getHeartbeatTimeout();
                    heartbeatTimeoutMillis.put(e.getEventId(), timeout.getSeconds() * 1000 + timeout.getNanos() / 1_000_000);
                }
                case EVENT_TYPE_ACTIVITY_TASK_STARTED -> {
                    var started = e.getActivityTaskStartedEventAttributes();
                    activityRetries += Math.max(0, started.getAttempt() - 1);
                    startedMillis.put(started.getScheduledEventId(), millis(e));
                }
                case EVENT_TYPE_ACTIVITY_TASK_COMPLETED -> heartbeats(
                        e.getActivityTaskCompletedEventAttributes().getScheduledEventId(), e, heartbeatTimeoutMillis, startedMillis);
                case EVENT_TYPE_ACTIVITY_TASK_FAILED -> heartbeats(
                        e.getActivityTaskFailedEventAttributes().getScheduledEventId(), e, heartbeatTimeoutMillis, startedMillis);
                case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT -> heartbeats(
                        e.getActivityTaskTimedOutEventAttributes().getScheduledEventId(), e, heartbeatTimeoutMillis, startedMillis);
                case EVENT_TYPE_ACTIVITY_TASK_CANCELED -> heartbeats(
                        e.getActivityTaskCanceledEventAttributes().getScheduledEventId(), e, heartbeatTimeoutMillis, startedMillis);
                case EVENT_TYPE_TIMER_STARTED -> timers++;
                case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED -> signalsReceived++;
                case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED -> signalsSent++;
                case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES, EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED -> otherActions++;
                case EVENT_TYPE_MARKER_RECORDED ->
                        localActivityTasks.add(e.getMarkerRecordedEventAttributes().getWorkflowTaskCompletedEventId());
                default -> {
                }
            }
        }
        otherActions += localActivityTasks.size();
        return children;
    }

    private void heartbeats(long scheduledId, HistoryEvent closed, Map<Long, Long> heartbeatTimeoutMillis,
            Map<Long, Long> startedMillis) {
        long timeout = heartbeatTimeoutMillis.getOrDefault(scheduledId, 0L);
        Long started = startedMillis.get(scheduledId);
        if (timeout > 0 && started != null) {
            long ran = Math.max(0, millis(closed) - started);
            heartbeatsEstimated += 1 + (int) (ran / (timeout * HEARTBEAT_THROTTLE));
        }
    }

    private static long millis(HistoryEvent e) {
        return e.getEventTime().getSeconds() * 1000 + e.getEventTime().getNanos() / 1_000_000;
    }
}
