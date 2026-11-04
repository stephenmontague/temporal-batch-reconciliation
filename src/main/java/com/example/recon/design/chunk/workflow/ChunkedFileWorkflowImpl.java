package com.example.recon.design.chunk.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.example.recon.design.chunk.activity.ChunkActivities;
import com.example.recon.dto.ChunkInput;
import com.example.recon.dto.ChunkResult;
import com.example.recon.dto.FileInput;
import com.example.recon.dto.FileResult;
import com.example.recon.dto.ReportSummary;

import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

@WorkflowImpl(taskQueues = "${recon.chunk-task-queue}")
public class ChunkedFileWorkflowImpl implements ChunkedFileWorkflow {

    static final int MAX_CHUNKS = 1000;

    private static final Logger log = Workflow.getLogger(ChunkedFileWorkflowImpl.class);

    private final ChunkActivities quick = Workflow.newActivityStub(
            ChunkActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .build());

    private String phase = "STARTING";

    @Override
    public FileResult reconcile(FileInput input) {
        long deadlineAt = Workflow.getInfo().getRunStartedTimestampMillis()
                + input.deadlineSeconds() * 1000;

        phase = "COUNTING";
        int totalRows = quick.countRows(input.fileRef());
        int chunkCount = (totalRows + input.batchSize() - 1) / input.batchSize();
        if (chunkCount > MAX_CHUNKS) {
            throw ApplicationFailure.newNonRetryableFailure(
                    chunkCount + " chunks is over the limit of " + MAX_CHUNKS + "; use a larger batch size",
                    "TOO_MANY_CHUNKS");
        }
        log.info("File {} has {} rows in {} chunk workflows", input.fileId(), totalRows, chunkCount);

        phase = "PROCESSING_CHUNKS";
        String parentId = Workflow.getInfo().getWorkflowId();
        List<Promise<ChunkResult>> running = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            int offset = i * input.batchSize();
            ChunkWorkflow chunk = Workflow.newChildWorkflowStub(ChunkWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId(parentId + "-chunk-" + i)
                            .build());
            running.add(Async.function(chunk::process, new ChunkInput(input.fileRef(), input.fileId(), i,
                    offset, Math.min(input.batchSize(), totalRows - offset), deadlineAt)));
        }
        Promise.allOf(running).get();

        int rowsInvalid = 0;
        int rowsPending = 0;
        int rowsSubmitted = 0;
        int rowsTimedOut = 0;
        boolean allAnswered = true;
        for (Promise<ChunkResult> p : running) {
            ChunkResult r = p.get();
            rowsInvalid += r.rowsInvalid();
            rowsPending += r.rowsPending();
            rowsSubmitted += r.rowsSubmitted();
            rowsTimedOut += r.rowsTimedOut();
            allAnswered &= r.allAnsweredBeforeDeadline();
        }
        String outcome = rowsTimedOut == 0 ? "COMPLETED" : "TIMED_OUT";

        phase = "REPORTING";
        ReportSummary report = quick.writeReport(input.fileId());

        phase = outcome;
        return new FileResult(input.fileId(), outcome, totalRows, rowsPending, rowsInvalid,
                rowsSubmitted, allAnswered, rowsTimedOut, report);
    }

    @Override
    public String phase() {
        return phase;
    }
}
