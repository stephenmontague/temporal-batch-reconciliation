package com.example.batchprocessing.cli.view;

import java.io.PrintStream;

import com.example.batchprocessing.cli.actions.ActionCount;

public final class ActionCountPrinter {

    // scripts/run-experiments.sh reads these columns by position
    public static final String CSV_HEADER = String.join(
            ",",
            "label",
            "workflow_id",
            "workflows",
            "total_actions",
            "workflow_starts",
            "child_starts",
            "activities",
            "activity_retries",
            "heartbeats_est",
            "timers",
            "signals_received",
            "signals_sent",
            "other_actions",
            "root_history_events",
            "max_child_history_events",
            "total_history_events",
            "wall_clock_seconds");

    private static final String CSV_ROW = "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f%n";

    public static void printCsv(String label, ActionCount count, PrintStream out) {
        out.println(CSV_HEADER);
        out.printf(
                CSV_ROW,
                label,
                count.workflowId(),
                count.workflows(),
                count.total(),
                count.workflowStarts(),
                count.childStarts(),
                count.activities(),
                count.activityRetries(),
                count.heartbeatsEstimated(),
                count.timers(),
                count.signalsReceived(),
                count.signalsSent(),
                count.otherActions(),
                count.rootHistoryEvents(),
                count.maxChildHistoryEvents(),
                count.totalHistoryEvents(),
                count.wallClockSeconds());
    }

    public static void printTable(ActionCount count, PrintStream out) {
        int childWorkflows = count.workflows() - 1;

        out.printf("%nWorkflow:                  %s (+ %d child workflows)%n", count.workflowId(), childWorkflows);
        out.printf("Estimated billable actions: %,d%n", count.total());
        out.printf("  Workflow starts:          %,d%n", count.workflowStarts());
        out.printf("  Child workflow starts:    %,d%n", count.childStarts());
        out.printf("  Activities scheduled:     %,d%n", count.activities());
        out.printf("  Activity retries:         %,d%n", count.activityRetries());
        out.printf("  Heartbeats (estimated):   %,d%n", count.heartbeatsEstimated());
        out.printf("  Timers:                   %,d%n", count.timers());
        out.printf("  Signals received:         %,d%n", count.signalsReceived());
        out.printf("  Signals sent:             %,d%n", count.signalsSent());
        out.printf("  Other:                    %,d%n", count.otherActions());
        out.printf(
                "History events: root %,d, largest child %,d, all workflows %,d%n",
                count.rootHistoryEvents(),
                count.maxChildHistoryEvents(),
                count.totalHistoryEvents());
        out.printf("Wall clock:     %.1f s%n", count.wallClockSeconds());
    }

    private ActionCountPrinter() {}
}
