package com.example.recon.listener;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.recon.dto.CounterUpdate;
import com.example.recon.dto.VendorAnswer;
import com.example.recon.service.TxnStore;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;

@Component
@Profile("vendor")
public class VendorCallbackListener {

    private static final Logger log = LoggerFactory.getLogger(VendorCallbackListener.class);

    private final TxnStore store;
    private final WorkflowClient client;

    public VendorCallbackListener(TxnStore store, WorkflowClient client) {
        this.store = store;
        this.client = client;
    }

    public void onAnswers(List<VendorAnswer> answers) {
        for (CounterUpdate update : store.applyAnswers(answers)) {
            if (update.remaining() == 0) {
                signalAllDone(update);
            }
        }
    }

    private void signalAllDone(CounterUpdate update) {
        log.info("Counter {} reached 0, signaling allDone on {}", update.counterKey(), update.workflowId());
        try {
            client.newUntypedWorkflowStub(update.workflowId()).signal("allDone");
        } catch (WorkflowNotFoundException e) {
            log.warn("Workflow {} is closed; dropping allDone signal", update.workflowId());
        } catch (Exception e) {
            log.warn("Could not signal allDone on {}: {}", update.workflowId(), e.getMessage());
        }
    }
}
