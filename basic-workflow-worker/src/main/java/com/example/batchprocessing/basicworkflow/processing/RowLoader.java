package com.example.batchprocessing.basicworkflow.processing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.batchprocessing.basicworkflow.dto.BatchRequest;
import com.example.batchprocessing.basicworkflow.dto.SeedProgress;
import com.example.batchprocessing.basicworkflow.dto.SeedResult;
import com.example.batchprocessing.core.dto.ParsedRow;
import com.example.batchprocessing.core.store.TxnStore;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.failure.ApplicationFailure;

// Reads a batch of rows from the CSV, validates them, and inserts the ones that aren't stored yet.
// Must be called from inside an activity: it heartbeats its progress, so a retry resumes where the last attempt stopped.
public class RowLoader {

    private static final Logger log = LoggerFactory.getLogger(RowLoader.class);

    // Rows per insert. The loader heartbeats after each insert with a SeedProgress checkpoint.
    //
    // How this relates to the 30-second heartbeat timeout (ReconciliationActivityOptions.LONG_RUNNING):
    //   - Each group must parse and insert in well under 30 s, or a healthy attempt would time out. It takes milliseconds.
    //   - The SDK throttles heartbeats to about one per 24 s (80% of the timeout): the first goes out at once, after that
    //     only the latest checkpoint is sent at each interval. A retry resumes from the last checkpoint that reached the
    //     server, which can be older than the last group; re-inserting is safe because existing rows are skipped.
    //   - So 200 rows sets how fine-grained the checkpoints can be; 30 s sets how fast a lost worker is noticed.
    private static final int WRITE_GROUP_SIZE = 200;

    private final TxnStore store;

    public RowLoader(TxnStore store) {
        this.store = store;
    }

    public int countRows(String fileRef) {
        try (BufferedReader reader = open(fileRef)) {
            skipHeader(reader);

            return countDataRows(reader);
        } catch (NoSuchFileException e) {
            throw ApplicationFailure.newNonRetryableFailure("File not found: " + fileRef, "FILE_NOT_FOUND");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // counterKey tags every inserted row with the pending counter that tracks it
    public SeedResult load(BatchRequest request, String counterKey) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        SeedProgress start = startingPoint(context, request);

        try (BufferedReader reader = open(request.fileRef())) {
            skipHeader(reader);
            skipDataRows(reader, start.nextOffset());

            SeedProgress finished = insertRemainingRows(reader, request, counterKey, start, context);

            return new SeedResult(finished.read(), finished.inserted(), finished.invalid());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Fresh attempt: the batch's first row. Retry: wherever the last heartbeat said it got to.
    private static SeedProgress startingPoint(ActivityExecutionContext context, BatchRequest request) {
        SeedProgress fresh = new SeedProgress(request.offset(), 0, 0, 0);
        SeedProgress start = context.getHeartbeatDetails(SeedProgress.class).orElse(fresh);
        boolean resuming = start.nextOffset() != request.offset();

        if (resuming) {
            int attempt = context.getInfo().getAttempt();
            log.info("Batch {}@{} resuming at offset {} (attempt {})", request.fileId(), request.offset(), start.nextOffset(), attempt);
        }

        return start;
    }

    private SeedProgress insertRemainingRows(
            BufferedReader reader,
            BatchRequest request,
            String counterKey,
            SeedProgress start,
            ActivityExecutionContext context) throws IOException {
        int endLine = request.offset() + request.size();
        int lineNo = start.nextOffset();
        int read = start.read();
        int inserted = start.inserted();
        int invalid = start.invalid();
        List<ParsedRow> group = new ArrayList<>(WRITE_GROUP_SIZE);

        while (lineNo < endLine) {
            String line = reader.readLine();

            if (line == null) {
                break;
            }

            if (line.isBlank()) {
                continue;
            }

            ParsedRow row = CsvRowParser.parse(request.fileId(), lineNo, line);
            group.add(row);
            read++;
            lineNo++;

            if (row.isInvalid()) {
                invalid++;
            }

            if (group.size() == WRITE_GROUP_SIZE) {
                inserted += insertAndClear(group, request.fileId(), counterKey);
                context.heartbeat(new SeedProgress(lineNo, read, inserted, invalid));
            }
        }

        if (!group.isEmpty()) {
            inserted += insertAndClear(group, request.fileId(), counterKey);
            context.heartbeat(new SeedProgress(lineNo, read, inserted, invalid));
        }

        return new SeedProgress(lineNo, read, inserted, invalid);
    }

    private int insertAndClear(List<ParsedRow> group, String fileId, String counterKey) {
        int inserted = store.insertRowsIfAbsent(fileId, counterKey, group);
        group.clear();

        return inserted;
    }

    private static BufferedReader open(String fileRef) throws IOException {
        Path file = Path.of(fileRef);

        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    private static void skipHeader(BufferedReader reader) throws IOException {
        reader.readLine();
    }

    private static int countDataRows(BufferedReader reader) throws IOException {
        int count = 0;
        String line = reader.readLine();

        while (line != null) {
            if (!line.isBlank()) {
                count++;
            }

            line = reader.readLine();
        }

        return count;
    }

    private static void skipDataRows(BufferedReader reader, int rows) throws IOException {
        int skipped = 0;

        while (skipped < rows) {
            String line = reader.readLine();

            if (line == null) {
                return;
            }

            if (!line.isBlank()) {
                skipped++;
            }
        }
    }
}
