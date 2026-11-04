package com.example.recon.design.chunk.workflow;

import com.example.recon.dto.FileInput;
import com.example.recon.dto.FileResult;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ChunkedFileWorkflow {

    @WorkflowMethod
    FileResult reconcile(FileInput input);

    @QueryMethod
    String phase();
}
