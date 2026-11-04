package com.example.recon.view;

import com.example.recon.dto.FileResult;
import java.io.PrintStream;

public final class FileResultPrinter {

    public static void print(FileResult r, PrintStream out) {
        out.printf("%nOutcome:            %s%n", r.outcome());
        out.printf("Rows in file:       %,d%n", r.totalRows());
        out.printf("Rows invalid:       %,d%n", r.rowsInvalid());
        out.printf("Rows pending:       %,d%n", r.rowsPending());
        out.printf("Rows submitted:     %,d%n", r.rowsSubmitted());
        out.printf("All done in time:   %s%n", r.allDoneBeforeDeadline());
        out.printf("Rows timed out:     %,d%n", r.rowsTimedOut());
        out.printf("Rows by state:      %s%n", r.report().rowsByState());
        out.printf("Failures by code:   %s%n", r.report().failuresByErrorCode());
        out.printf("Report:             %s%n", r.report().reportPath());
    }

    private FileResultPrinter() {}
}
