package com.example.recon.design.file.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;

import com.example.recon.design.file.activity.ReconciliationActivities;
import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.FileInput;
import com.example.recon.dto.FileResult;
import com.example.recon.dto.ReportSummary;
import com.example.recon.dto.SeedResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

@WorkflowImpl(taskQueues = "${recon.file-task-queue}")
public class ReconciliationFileWorkflowImpl implements ReconciliationFileWorkflow {

    private static final Logger log = Workflow.getLogger(ReconciliationFileWorkflowImpl.class);

    private final ReconciliationActivities chunkActivities = Workflow.newActivityStub(
            ReconciliationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .build());

    private final ReconciliationActivities quickActivities = Workflow.newActivityStub(
            ReconciliationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .build());

    private boolean allDone;
    private String phase = "STARTING";

    @Override
    public FileResult reconcile(FileInput input) {
        long deadlineAt = Workflow.getInfo().getRunStartedTimestampMillis()
                + input.deadlineSeconds() * 1000;

        phase = "COUNTING";
        int totalRows = quickActivities.countRows(input.fileRef());
        List<ChunkRequest> chunks = chunksOf(input, totalRows);
        log.info("File {} has {} rows in {} chunks", input.fileId(), totalRows, chunks.size());

        phase = "SEEDING";
        int rowsInvalid = 0;
        for (SeedResult s : runCapped(chunks, input.maxParallel(),
                c -> Async.function(chunkActivities::seedChunk, c))) {
            rowsInvalid += s.invalid();
        }

        phase = "COUNTING_PENDING";
        int rowsPending = quickActivities.setCounter(input.fileId());

        phase = "SUBMITTING";
        int rowsSubmitted = 0;
        for (Integer queued : runCapped(chunks, input.maxParallel(),
                c -> Async.function(chunkActivities::submitBatch, c))) {
            rowsSubmitted += queued;
        }

        phase = "WAITING_FOR_VENDOR";
        if (!allDone && quickActivities.getPending(input.fileId()) == 0) {
            allDone = true;
        }

        long remainingMillis = Math.max(0, deadlineAt - Workflow.currentTimeMillis());
        boolean allDoneBeforeDeadline = allDone || Workflow.await(Duration.ofMillis(remainingMillis), () -> allDone);

        phase = "SWEEPING";
        int rowsTimedOut = quickActivities.markTimeouts(input.fileId());
        String outcome = rowsTimedOut == 0 ? "COMPLETED" : "TIMED_OUT";

        phase = "REPORTING";
        ReportSummary report = quickActivities.writeReport(input.fileId());

        phase = outcome;
        return new FileResult(input.fileId(), outcome, totalRows, rowsPending, rowsInvalid,
                rowsSubmitted, allDoneBeforeDeadline, rowsTimedOut, report);
    }

    @Override
    public void allDone() {
        allDone = true;
    }

    @Override
    public String phase() {
        return phase;
    }

    private static List<ChunkRequest> chunksOf(FileInput input, int totalRows) {
        List<ChunkRequest> chunks = new ArrayList<>();
        for (int offset = 0; offset < totalRows; offset += input.batchSize()) {
            int size = Math.min(input.batchSize(), totalRows - offset);
            chunks.add(new ChunkRequest(input.fileRef(), input.fileId(), offset, size));
        }
        return chunks;
    }

    private static <T> List<T> runCapped(List<ChunkRequest> chunks, int cap,
            Function<ChunkRequest, Promise<T>> start) {
        List<Promise<T>> all = new ArrayList<>();
        List<Promise<T>> inFlight = new ArrayList<>();
        for (ChunkRequest chunk : chunks) {
            if (inFlight.size() >= cap) {
                Promise.anyOf(inFlight).get();
                inFlight.removeIf(Promise::isCompleted);
            }
            Promise<T> p = start.apply(chunk);
            all.add(p);
            inFlight.add(p);
        }
        Promise.allOf(all).get();
        List<T> results = new ArrayList<>();
        for (Promise<T> p : all) {
            results.add(p.get());
        }
        return results;
    }
}
