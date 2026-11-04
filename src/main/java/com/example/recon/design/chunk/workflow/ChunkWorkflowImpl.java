package com.example.recon.design.chunk.workflow;

import java.time.Duration;

import com.example.recon.design.chunk.activity.ChunkActivities;
import com.example.recon.dto.ChunkInput;
import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.ChunkResult;
import com.example.recon.dto.ChunkSummary;
import com.example.recon.dto.StageResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

@WorkflowImpl(taskQueues = "${recon.chunk-task-queue}")
public class ChunkWorkflowImpl implements ChunkWorkflow {

    private final ChunkActivities work = Workflow.newActivityStub(
            ChunkActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .build());

    private final ChunkActivities quick = Workflow.newActivityStub(
            ChunkActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .build());

    private boolean allDone;

    @Override
    public ChunkResult process(ChunkInput input) {
        String counterKey = Workflow.getInfo().getWorkflowId();
        ChunkRequest request = new ChunkRequest(input.fileRef(), input.fileId(), input.offset(), input.size());

        StageResult staged = work.stageChunk(request, counterKey);

        int submitted = work.submitChunk(request);

        long remainingMillis = Math.max(0, input.deadlineAtMillis() - Workflow.currentTimeMillis());
        boolean allAnswered = staged.pending() == 0 || allDone
                || Workflow.await(Duration.ofMillis(remainingMillis), () -> allDone);

        ChunkSummary summary = quick.finishChunk(counterKey);
        return new ChunkResult(input.chunkIndex(), staged.read(), staged.invalid(), staged.pending(),
                submitted, allAnswered, summary.timedOut(), summary.rowsByState(),
                summary.failuresByErrorCode());
    }

    @Override
    public void allDone() {
        allDone = true;
    }
}
