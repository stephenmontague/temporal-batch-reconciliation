package com.example.batchprocessing.basicworkflow.processing;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.example.batchprocessing.core.dto.ReportSummary;
import com.example.batchprocessing.core.store.TxnStore;

// Writes the per-row CSV report for a file: <reportDir>/<fileId>-report.csv.
// It writes to a temp file and moves it into place, so a retry never leaves half a report behind.
public class ReportWriter {

    private static final String HEADER = "line_no,transaction_id,state,error_code\n";

    private final TxnStore store;
    private final Path reportDir;

    public ReportWriter(TxnStore store, Path reportDir) {
        this.store = store;
        this.reportDir = reportDir;
    }

    public ReportSummary write(String fileId) {
        Path target = reportDir.resolve(fileId + "-report.csv");
        Path partial = reportDir.resolve(fileId + "-report.csv.tmp");

        try {
            Files.createDirectories(reportDir);
            writeRows(fileId, partial);
            Files.move(partial, target, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String reportPath = target.toAbsolutePath().toString();
        Map<String, Integer> rowsByState = store.countByStateForFile(fileId);
        Map<String, Integer> failuresByErrorCode = store.countFailuresForFile(fileId);

        return new ReportSummary(reportPath, rowsByState, failuresByErrorCode);
    }

    private void writeRows(String fileId, Path out) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write(HEADER);

            store.forEachRow(fileId, (txnId, lineNo, state, errorCode) -> {
                String line = reportLine(txnId, lineNo, state, errorCode);
                writer.write(line);
            });
        }
    }

    private static String reportLine(String txnId, int lineNo, String state, String errorCode) {
        String error = errorCode == null ? "" : errorCode;

        return lineNo + "," + txnId + "," + state + "," + error + "\n";
    }
}
