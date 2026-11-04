package com.example.recon.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.ParsedRow;
import com.example.recon.dto.SeedProgress;
import com.example.recon.dto.SeedResult;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.failure.ApplicationFailure;

public class RowLoader {

    private static final Logger log = LoggerFactory.getLogger(RowLoader.class);

    private static final int WRITE_BATCH = 200;

    private final TxnStore store;

    public RowLoader(TxnStore store) {
        this.store = store;
    }

    public int countRows(String fileRef) {
        try (BufferedReader r = open(fileRef)) {
            int count = 0;
            r.readLine();
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (!line.isBlank()) {
                    count++;
                }
            }
            return count;
        } catch (NoSuchFileException e) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "File not found: " + fileRef, "FILE_NOT_FOUND");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public SeedResult load(ChunkRequest req, String counterKey) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        SeedProgress start = ctx.getHeartbeatDetails(SeedProgress.class)
                .orElse(new SeedProgress(req.offset(), 0, 0, 0));
        if (start.nextOffset() != req.offset()) {
            log.info("Chunk {}@{} resuming at offset {} (attempt {})",
                    req.fileId(), req.offset(), start.nextOffset(), ctx.getInfo().getAttempt());
        }
        int read = start.read();
        int inserted = start.inserted();
        int invalid = start.invalid();
        int end = req.offset() + req.size();
        try (BufferedReader r = open(req.fileRef())) {
            r.readLine();
            skipDataRows(r, start.nextOffset());
            List<ParsedRow> pending = new ArrayList<>(WRITE_BATCH);
            int lineNo = start.nextOffset();
            while (lineNo < end) {
                String line = r.readLine();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                ParsedRow row = parse(req.fileId(), lineNo, line);
                pending.add(row);
                read++;
                if (row.errorCode() != null) {
                    invalid++;
                }
                lineNo++;
                if (pending.size() == WRITE_BATCH) {
                    inserted += store.insertRowsIfAbsent(req.fileId(), counterKey, pending);
                    pending.clear();
                    ctx.heartbeat(new SeedProgress(lineNo, read, inserted, invalid));
                }
            }
            if (!pending.isEmpty()) {
                inserted += store.insertRowsIfAbsent(req.fileId(), counterKey, pending);
                ctx.heartbeat(new SeedProgress(lineNo, read, inserted, invalid));
            }
            return new SeedResult(read, inserted, invalid);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedReader open(String fileRef) throws IOException {
        return Files.newBufferedReader(Path.of(fileRef), StandardCharsets.UTF_8);
    }

    private static void skipDataRows(BufferedReader r, int rows) throws IOException {
        int skipped = 0;
        while (skipped < rows) {
            String line = r.readLine();
            if (line == null) {
                return;
            }
            if (!line.isBlank()) {
                skipped++;
            }
        }
    }

    static ParsedRow parse(String fileId, int lineNo, String line) {
        String[] f = line.split(",", -1);
        String txnId = f.length > 0 && !f[0].isBlank() ? f[0].trim() : fileId + ":line:" + lineNo;
        if (f.length != 6) {
            return new ParsedRow(txnId, lineNo, null, null, null, null, null, "BAD_COLUMN_COUNT");
        }
        String accountId = f[1].trim();
        String currency = f[3].trim();
        String date = f[4].trim();
        String merchant = f[5].trim();
        Long cents;
        try {
            cents = new BigDecimal(f[2].trim()).movePointRight(2).longValueExact();
        } catch (Exception e) {
            return new ParsedRow(txnId, lineNo, accountId, null, currency, date, merchant,
                    "BAD_AMOUNT");
        }
        if (accountId.isEmpty()) {
            return new ParsedRow(txnId, lineNo, accountId, cents, currency, date, merchant,
                    "MISSING_ACCOUNT");
        }
        if (!currency.matches("[A-Z]{3}")) {
            return new ParsedRow(txnId, lineNo, accountId, cents, currency, date, merchant,
                    "BAD_CURRENCY");
        }
        try {
            LocalDate.parse(date);
        } catch (Exception e) {
            return new ParsedRow(txnId, lineNo, accountId, cents, currency, date, merchant,
                    "BAD_DATE");
        }
        return new ParsedRow(txnId, lineNo, accountId, cents, currency, date, merchant, null);
    }
}
