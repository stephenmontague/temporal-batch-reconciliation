package com.example.batchprocessing.core.contract;

// The names other processes use to reach a workflow. The CLI starts workflows by type name,
// and the vendor's callback listener signals them by name. Each worker pins its workflow
// interfaces to these constants, so a rename on either side is a compile error, not a silent miss.
public final class WorkflowNames {

    // basic-workflow-worker: one workflow reconciles the whole file
    public static final String BASIC_WORKFLOW = "BasicWorkflow";

    // Sent by the vendor's callback listener when a pending counter reaches zero
    public static final String ALL_DONE_SIGNAL = "allDone";

    public static final String PHASE_QUERY = "phase";

    private WorkflowNames() {}
}
