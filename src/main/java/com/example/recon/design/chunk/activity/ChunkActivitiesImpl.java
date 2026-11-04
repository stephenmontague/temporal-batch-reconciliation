package com.example.recon.design.chunk.activity;

import java.nio.file.Path;
import java.util.Map;

import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.ChunkSummary;
import com.example.recon.dto.ReportSummary;
import com.example.recon.dto.SeedResult;
import com.example.recon.dto.StageResult;
import com.example.recon.service.ReportWriter;
import com.example.recon.service.RowLoader;
import com.example.recon.service.TxnStore;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;

// The chunk design's real work: file, database, vendor queue.
// Each method is one activity. Workflows call it; if it throws, Temporal retries it,
// so every method is safe to run twice.
@ActivityImpl(taskQueues = "${recon.chunk-task-queue}")
public class ChunkActivitiesImpl implements ChunkActivities {

    private final TxnStore store;
    private final RowLoader loader;
    private final ReportWriter reports;

    public ChunkActivitiesImpl(TxnStore store, Path reportDir) {
        this.store = store;
        this.loader = new RowLoader(store);
        this.reports = new ReportWriter(store, reportDir);
    }

    // File workflow, first step: how many data rows are in the file?
    @Override
    public int countRows(String fileRef) {
        return loader.countRows(fileRef);
    }

    // Chunk step 1: save the chunk's rows, then start its "waiting on vendor" counter.
    // counterKey is this chunk workflow's ID.
    @Override
    public StageResult stageChunk(ChunkRequest request, String counterKey) {
        // Parse, validate, insert new rows. Heartbeats every 200 rows; a retry resumes there.
        SeedResult seeded = loader.load(request, counterKey);

        // Save this workflow's ID with the counter, so the vendor listener knows whom to signal at zero.
        String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
        int pending = store.initCounter(counterKey, workflowId);

        return new StageResult(seeded.read(), seeded.inserted(), seeded.invalid(), pending);
    }

    // Chunk step 2: queue the chunk's rows for the vendor. Rows already queued are skipped.
    @Override
    public int submitChunk(ChunkRequest request) {
        // Tells Temporal this worker is alive (billed as one action).
        Activity.getExecutionContext().heartbeat(request.offset());

        int fromLine = request.offset();
        int toLine = request.offset() + request.size();
        return store.enqueueOutbound(request.fileId(), fromLine, toLine);
    }

    // Chunk last step, after waiting for the vendor: unanswered rows become TIMED_OUT,
    // then tally the chunk's rows by state and its failures by error code.
    @Override
    public ChunkSummary finishChunk(String counterKey) {
        int timedOut = store.markTimeouts(counterKey);

        Map<String, Integer> rowsByState = store.countByStateForCounter(counterKey);
        Map<String, Integer> failuresByCode = store.countFailuresForCounter(counterKey);

        return new ChunkSummary(timedOut, rowsByState, failuresByCode);
    }

    // File workflow, last step: per-row CSV report for the whole file.
    @Override
    public ReportSummary writeReport(String fileId) {
        return reports.write(fileId);
    }
}
