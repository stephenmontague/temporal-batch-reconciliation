package com.example.batchprocessing.basicworkflow.activity;

import java.time.Duration;
import java.util.Map;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

// Timeouts and retry policies for every activity call. Follows Temporal's guidance:
//   - Every activity sets a Start-To-Close timeout longer than its slowest single attempt.
//   - Long-running activities also heartbeat, with a heartbeat timeout of 2-3x how often they heartbeat,
//     so a dead worker is noticed in seconds instead of after the whole Start-To-Close.
//   - Retries are unlimited; an activity that can never succeed throws a non-retryable failure instead (a missing file).
//     The one exception is retryFailedRows, where failing again is a normal result rather than a fault (see FAILED_ROW_RETRY).
//
// Workflows get one activity stub with DEFAULT options, plus the per-activity-type overrides in BY_ACTIVITY_TYPE
// (see BasicWorkflowImpl). Any activity not listed there uses DEFAULT.
public final class ReconciliationActivityOptions {

    // Back off 1s, 2s, 4s, ... up to one minute between attempts, until the activity succeeds
    public static final RetryOptions RETRY_POLICY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofMinutes(1))
            .build();

    // One database or file call: if an attempt hasn't finished in 60 seconds, its worker is assumed lost and it's retried
    public static final ActivityOptions DEFAULT = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setRetryOptions(RETRY_POLICY)
            .build();

    // Works through a whole batch of rows, so an attempt may take minutes. It must heartbeat at least every 30 seconds
    // (RowLoader heartbeats after every 200-row insert, which takes milliseconds), or Temporal fails the attempt and retries it.
    public static final ActivityOptions LONG_RUNNING = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setHeartbeatTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RETRY_POLICY)
            .build();

    // Retrying rows the vendor rejected with a retryable error code. Each attempt sends them again and waits for the answers.
    // If some fail again, the attempt throws, and the next one starts after 30 s, then 60 s, then 2 min: 4 attempts in all.
    // After the last one the workflow stops trying and leaves those rows FAILED, so this policy has a limit on purpose.
    public static final RetryOptions FAILED_ROW_RETRY_POLICY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(30))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofMinutes(5))
            .setMaximumAttempts(4)
            .build();

    // An attempt waits for the vendor's answers (up to 30 s from the fake vendor), heartbeating while it waits.
    // If the vendor hasn't answered within 2 minutes, the attempt ends and the next one sends the rows again.
    public static final ActivityOptions FAILED_ROW_RETRY = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setHeartbeatTimeout(Duration.ofSeconds(30))
            .setRetryOptions(FAILED_ROW_RETRY_POLICY)
            .build();

    // Activities whose options differ from DEFAULT, keyed by activity type
    public static final Map<String, ActivityOptions> BY_ACTIVITY_TYPE = Map.of(
            ReconciliationActivities.SEED_BATCH, LONG_RUNNING,
            ReconciliationActivities.SUBMIT_BATCH, LONG_RUNNING,
            ReconciliationActivities.RETRY_FAILED_ROWS, FAILED_ROW_RETRY);

    private ReconciliationActivityOptions() {}
}
