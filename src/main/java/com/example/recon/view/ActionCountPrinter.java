package com.example.recon.view;

import java.io.PrintStream;

import com.example.recon.dto.ActionCount;

public final class ActionCountPrinter {

    public static final String CSV_HEADER = "label,workflow_id,workflows,total_actions,workflow_starts,"
            + "child_starts,activities,activity_retries,heartbeats_est,timers,signals_received,"
            + "signals_sent,other_actions,root_history_events,max_child_history_events,"
            + "total_history_events,wall_clock_seconds";

    public static void printCsv(String label, ActionCount c, PrintStream out) {
        out.println(CSV_HEADER);
        out.printf("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f%n", label, c.workflowId(),
                c.workflows(), c.total(), c.workflowStarts(), c.childStarts(), c.activities(),
                c.activityRetries(), c.heartbeatsEstimated(), c.timers(), c.signalsReceived(),
                c.signalsSent(), c.otherActions(), c.rootHistoryEvents(), c.maxChildHistoryEvents(),
                c.totalHistoryEvents(), c.wallClockSeconds());
    }

    public static void printTable(ActionCount c, PrintStream out) {
        out.printf("%nWorkflow:                  %s (+ %d child workflows)%n", c.workflowId(), c.workflows() - 1);
        out.printf("Estimated billable actions: %,d%n", c.total());
        out.printf("  Workflow starts:          %,d%n", c.workflowStarts());
        out.printf("  Child workflow starts:    %,d%n", c.childStarts());
        out.printf("  Activities scheduled:     %,d%n", c.activities());
        out.printf("  Activity retries:         %,d%n", c.activityRetries());
        out.printf("  Heartbeats (estimated):   %,d%n", c.heartbeatsEstimated());
        out.printf("  Timers:                   %,d%n", c.timers());
        out.printf("  Signals received:         %,d%n", c.signalsReceived());
        out.printf("  Signals sent:             %,d%n", c.signalsSent());
        out.printf("  Other:                    %,d%n", c.otherActions());
        out.printf("History events: root %,d, largest child %,d, all workflows %,d%n",
                c.rootHistoryEvents(), c.maxChildHistoryEvents(), c.totalHistoryEvents());
        out.printf("Wall clock:     %.1f s%n", c.wallClockSeconds());
    }

    private ActionCountPrinter() {}
}
