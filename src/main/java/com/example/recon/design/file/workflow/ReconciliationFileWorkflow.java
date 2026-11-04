package com.example.recon.design.file.workflow;

import com.example.recon.dto.FileInput;
import com.example.recon.dto.FileResult;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ReconciliationFileWorkflow {

    @WorkflowMethod
    FileResult reconcile(FileInput input);

    @SignalMethod
    void allDone();

    @QueryMethod
    String phase();
}
