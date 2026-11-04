package com.example.recon.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.example.recon.dto.ReportSummary;

public class ReportWriter {

    private final TxnStore store;
    private final Path reportDir;

    public ReportWriter(TxnStore store, Path reportDir) {
        this.store = store;
        this.reportDir = reportDir;
    }

    public ReportSummary write(String fileId) {
        try {
            Files.createDirectories(reportDir);
            Path target = reportDir.resolve(fileId + "-report.csv");
            Path tmp = reportDir.resolve(fileId + "-report.csv.tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write("line_no,transaction_id,state,error_code\n");
                store.forEachRow(fileId, (txnId, lineNo, state, errorCode) -> {
                    w.write(lineNo + "," + txnId + "," + state + ","
                            + (errorCode == null ? "" : errorCode) + "\n");
                });
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return new ReportSummary(target.toAbsolutePath().toString(),
                    store.countByState(fileId), store.countFailuresByErrorCode(fileId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
