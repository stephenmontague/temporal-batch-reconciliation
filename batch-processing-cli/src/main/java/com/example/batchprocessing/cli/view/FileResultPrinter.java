package com.example.batchprocessing.cli.view;

import java.io.PrintStream;

import com.example.batchprocessing.core.dto.FileResult;
import com.example.batchprocessing.core.dto.RetryOutcome;

public final class FileResultPrinter {

    // scripts/run-experiments.sh reads the "Outcome:" line
    public static void print(FileResult result, PrintStream out) {
        out.printf("%nOutcome:            %s%n", result.outcome());
        out.printf("Rows in file:       %,d%n", result.totalRows());
        out.printf("Rows invalid:       %,d%n", result.rowsInvalid());
        out.printf("Rows pending:       %,d%n", result.rowsPending());
        out.printf("Rows submitted:     %,d%n", result.rowsSubmitted());
        out.printf("All done in time:   %s%n", result.allDoneBeforeDeadline());
        out.printf("Rows timed out:     %,d%n", result.rowsTimedOut());
        out.printf("Retry:              %s%n", describe(result.retry()));
        out.printf("Rows by state:      %s%n", result.report().rowsByState());
        out.printf("Failures by code:   %s%n", result.report().failuresByErrorCode());
        out.printf("Report:             %s%n", result.report().reportPath());
    }

    // NOT_NEEDED, SUCCEEDED (after 2 attempts), GAVE_UP (after 4 attempts)
    private static String describe(RetryOutcome retry) {
        boolean noAttempts = retry.attempts() == 0;

        if (noAttempts) {
            return retry.status();
        }

        String attemptWord = retry.attempts() == 1 ? "attempt" : "attempts";

        return String.format("%s (after %d %s)", retry.status(), retry.attempts(), attemptWord);
    }

    private FileResultPrinter() {}
}
