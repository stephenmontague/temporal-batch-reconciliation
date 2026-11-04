package com.example.batchprocessing.basicworkflow.workflow;

import com.example.batchprocessing.core.contract.WorkflowNames;
import com.example.batchprocessing.core.dto.FileInput;
import com.example.batchprocessing.core.dto.FileResult;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface BasicWorkflow {

    @WorkflowMethod(name = WorkflowNames.BASIC_WORKFLOW)
    FileResult reconcile(FileInput input);

    @SignalMethod(name = WorkflowNames.ALL_DONE_SIGNAL)
    void allDone();

    @QueryMethod(name = WorkflowNames.PHASE_QUERY)
    String phase();
}
