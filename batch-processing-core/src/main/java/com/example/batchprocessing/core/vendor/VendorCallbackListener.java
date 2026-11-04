package com.example.batchprocessing.core.vendor;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.batchprocessing.core.contract.WorkflowNames;
import com.example.batchprocessing.core.dto.CounterUpdate;
import com.example.batchprocessing.core.dto.VendorAnswer;
import com.example.batchprocessing.core.store.TxnStore;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;

// Stands in for the vendor's callback endpoint. Records each answer, and when a pending counter reaches zero,
// signals allDone to the workflow saved on that counter: the file workflow, or one batch workflow.
public class VendorCallbackListener {

    private static final Logger log = LoggerFactory.getLogger(VendorCallbackListener.class);

    private final TxnStore store;
    private final WorkflowClient client;

    public VendorCallbackListener(TxnStore store, WorkflowClient client) {
        this.store = store;
        this.client = client;
    }

    public void onAnswers(List<VendorAnswer> answers) {
        List<CounterUpdate> updates = store.applyAnswers(answers);

        for (CounterUpdate update : updates) {
            boolean everyRowAnswered = update.remaining() == 0;

            if (everyRowAnswered) {
                signalAllDone(update);
            }
        }
    }

    private void signalAllDone(CounterUpdate update) {
        String workflowId = update.workflowId();
        log.info("Counter {} reached 0, signaling {} on {}", update.counterKey(), WorkflowNames.ALL_DONE_SIGNAL, workflowId);

        try {
            client.newUntypedWorkflowStub(workflowId).signal(WorkflowNames.ALL_DONE_SIGNAL);
        } catch (WorkflowNotFoundException e) {
            log.warn("Workflow {} is closed; dropping {} signal", workflowId, WorkflowNames.ALL_DONE_SIGNAL);
        } catch (Exception e) {
            log.warn("Could not signal {} on {}: {}", WorkflowNames.ALL_DONE_SIGNAL, workflowId, e.getMessage());
        }
    }
}
