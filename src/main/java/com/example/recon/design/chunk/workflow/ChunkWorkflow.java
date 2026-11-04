package com.example.recon.design.chunk.workflow;

import com.example.recon.dto.ChunkInput;
import com.example.recon.dto.ChunkResult;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ChunkWorkflow {

    @WorkflowMethod
    ChunkResult process(ChunkInput input);

    @SignalMethod
    void allDone();
}
