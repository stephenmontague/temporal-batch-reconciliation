package com.example.recon.design.file.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.recon.design.file.activity.ReconciliationActivitiesImpl;
import com.example.recon.dto.ActionCount;
import com.example.recon.dto.FakeVendorSettings;
import com.example.recon.dto.FileInput;
import com.example.recon.dto.FileResult;
import com.example.recon.listener.VendorCallbackListener;
import com.example.recon.service.ActionCounter;
import com.example.recon.service.CsvGenerator;
import com.example.recon.service.FakeVendor;
import com.example.recon.service.TestStores;
import com.example.recon.service.TxnStore;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;

class ReconciliationFileWorkflowTest {

    private static final String TASK_QUEUE = "reconciliation-test";
    private static final int ROWS = 2_500;

    @TempDir
    Path tmp;

    private TestWorkflowEnvironment env;
    private FakeVendor vendor;
    private ConfigurableApplicationContext storeContext;

    @AfterEach
    void tearDown() throws Exception {
        if (vendor != null) {
            vendor.stop();
        }
        if (env != null) {
            env.close();
        }
        if (storeContext != null) {
            storeContext.close();
        }
    }

    private TxnStore startEnv(boolean timeSkipping) {
        env = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder().setUseTimeskipping(timeSkipping).build());
        storeContext = TestStores.open(tmp.resolve("recon.db"));
        TxnStore store = storeContext.getBean(TxnStore.class);
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ReconciliationFileWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new ReconciliationActivitiesImpl(store, tmp.resolve("reports")));
        env.start();
        return store;
    }

    private void startVendor(TxnStore store, double failureRate) {
        FakeVendorSettings settings = new FakeVendorSettings(0, 0.5, failureRate,
                List.of("ACCOUNT_LOCKED", "TIMEOUT"), 100, 2000, 7);
        vendor = new FakeVendor(store, new VendorCallbackListener(store, env.getWorkflowClient()),
                settings);
        vendor.start();
    }

    private Path generate(String name) throws Exception {
        Path file = tmp.resolve(name + ".csv");
        CsvGenerator.generate(file, ROWS, 0.01, 42);
        return file;
    }

    private FileResult run(String workflowId, Path file, long deadlineSeconds) throws Exception {
        WorkflowClient client = env.getWorkflowClient();
        ReconciliationFileWorkflow wf = client.newWorkflowStub(
                ReconciliationFileWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
        String fileId = file.getFileName().toString().replace(".csv", "");
        WorkflowClient.start(wf::reconcile,
                new FileInput(file.toString(), fileId, 500, 3, deadlineSeconds));
        return client.newUntypedWorkflowStub(workflowId)
                .getResultAsync(FileResult.class)
                .get(90, TimeUnit.SECONDS);
    }

    @Test
    void completesWhenVendorAnswersEveryRow() throws Exception {
        TxnStore store = startEnv(false);
        startVendor(store, 0.05);
        Path file = generate("happy");

        FileResult r = run("recon-happy", file, 120);

        assertEquals("COMPLETED", r.outcome());
        assertTrue(r.allDoneBeforeDeadline());
        assertEquals(ROWS, r.totalRows());
        assertEquals(0, r.rowsTimedOut());
        assertEquals(r.rowsPending(), r.rowsSubmitted());
        Map<String, Integer> byState = r.report().rowsByState();
        int answered = byState.getOrDefault("COMPLETED", 0) + byState.getOrDefault("FAILED", 0);
        assertEquals(r.rowsPending(), answered);
        assertEquals(r.rowsInvalid(), byState.getOrDefault("INVALID", 0));
        assertEquals(ROWS, answered + r.rowsInvalid());
        assertTrue(byState.getOrDefault("FAILED", 0) > 0, "expected some vendor failures");
        assertEquals(ROWS + 1, Files.readAllLines(Path.of(r.report().reportPath())).size());
        System.out.println("happy path: " + byState + " failures " + r.report().failuresByErrorCode());

        WorkflowReplayer.replayWorkflowExecution(
                env.getWorkflowClient().fetchHistory("recon-happy"),
                ReconciliationFileWorkflowImpl.class);

        ActionCount actions = new ActionCounter(env.getWorkflowClient()).count("recon-happy");
        assertEquals(1, actions.workflows());
        assertEquals(1, actions.workflowStarts());
        assertEquals(0, actions.childStarts());
        assertEquals(15, actions.activities());
        assertEquals(1, actions.signalsReceived());
    }

    @Test
    void deadlineMarksStragglersTimedOut() throws Exception {
        TxnStore store = startEnv(true);
        Path file = generate("nobody-answers");

        FileResult r = run("recon-nobody-answers", file, 7200);

        assertEquals("TIMED_OUT", r.outcome());
        assertFalse(r.allDoneBeforeDeadline());
        assertEquals(r.rowsPending(), r.rowsTimedOut());
        assertEquals(r.rowsPending(), r.report().rowsByState().get("TIMED_OUT"));
        assertEquals(r.rowsPending(), store.getPending("nobody-answers"));
    }

    @Test
    void rerunOfFinishedFileClosesImmediatelyViaCounterGuard() throws Exception {
        TxnStore store = startEnv(false);
        startVendor(store, 0.0);
        Path file = generate("rerun");
        FileResult first = run("recon-rerun", file, 120);
        assertEquals("COMPLETED", first.outcome());
        vendor.stop();
        vendor = null;

        long started = System.currentTimeMillis();
        FileResult second = run("recon-rerun-2", file, 120);
        long elapsedMillis = System.currentTimeMillis() - started;

        assertEquals("COMPLETED", second.outcome());
        assertTrue(second.allDoneBeforeDeadline());
        assertEquals(0, second.rowsSubmitted());
        assertTrue(elapsedMillis < 30_000, "took " + elapsedMillis + " ms");
    }
}
