package com.example.recon.design.chunk.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.recon.design.chunk.activity.ChunkActivitiesImpl;
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

class ChunkedFileWorkflowTest {

    private static final String TASK_QUEUE = "reconciliation-chunk-test";
    private static final int ROWS = 2_500;
    private static final int CHUNK_SIZE = 500;

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
        worker.registerWorkflowImplementationTypes(ChunkedFileWorkflowImpl.class, ChunkWorkflowImpl.class);
        worker.registerActivitiesImplementations(new ChunkActivitiesImpl(store, tmp.resolve("reports")));
        env.start();
        return store;
    }

    private void startVendor(TxnStore store) {
        FakeVendorSettings settings = new FakeVendorSettings(0, 2, 0.05,
                List.of("ACCOUNT_LOCKED", "TIMEOUT"), 100, 2000, 11);
        vendor = new FakeVendor(store, new VendorCallbackListener(store, env.getWorkflowClient()), settings);
        vendor.start();
    }

    private FileResult run(String workflowId, String fileId, long deadlineSeconds) throws Exception {
        Path file = tmp.resolve(fileId + ".csv");
        CsvGenerator.generate(file, ROWS, 0.01, 42);
        WorkflowClient client = env.getWorkflowClient();
        ChunkedFileWorkflow wf = client.newWorkflowStub(ChunkedFileWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE).build());
        WorkflowClient.start(wf::reconcile, new FileInput(file.toString(), fileId, CHUNK_SIZE, 10, deadlineSeconds));
        return client.newUntypedWorkflowStub(workflowId)
                .getResultAsync(FileResult.class)
                .get(90, TimeUnit.SECONDS);
    }

    @Test
    void chunksCompleteWhenVendorAnswersEveryRow() throws Exception {
        TxnStore store = startEnv(false);
        startVendor(store);

        FileResult r = run("recon-chunked", "chunked", 120);

        assertEquals("COMPLETED", r.outcome());
        assertTrue(r.allDoneBeforeDeadline());
        assertEquals(ROWS, r.totalRows());
        assertEquals(0, r.rowsTimedOut());
        Map<String, Integer> byState = r.report().rowsByState();
        int answered = byState.getOrDefault("COMPLETED", 0) + byState.getOrDefault("FAILED", 0);
        assertEquals(r.rowsPending(), answered);
        assertEquals(ROWS, answered + r.rowsInvalid());

        ActionCount actions = new ActionCounter(env.getWorkflowClient()).count("recon-chunked");
        int chunks = ROWS / CHUNK_SIZE;
        assertEquals(chunks + 1, actions.workflows());
        assertEquals(chunks + 1, actions.workflowStarts());
        assertEquals(chunks, actions.childStarts());
        assertEquals(2 + chunks * 3, actions.activities());
        assertTrue(actions.signalsReceived() >= chunks, "each chunk gets at least its final signal");
        System.out.println("chunked actions: " + actions + " total " + actions.total());

        WorkflowReplayer.replayWorkflowExecution(
                env.getWorkflowClient().fetchHistory("recon-chunked"), ChunkedFileWorkflowImpl.class);
        WorkflowReplayer.replayWorkflowExecution(
                env.getWorkflowClient().fetchHistory("recon-chunked-chunk-0"), ChunkWorkflowImpl.class);
    }

    @Test
    void deadlineTimesOutEveryChunk() throws Exception {
        TxnStore store = startEnv(true);

        FileResult r = run("recon-chunked-silent", "chunked-silent", 7200);

        assertEquals("TIMED_OUT", r.outcome());
        assertFalse(r.allDoneBeforeDeadline());
        assertEquals(r.rowsPending(), r.rowsTimedOut());
        assertEquals(r.rowsPending(), r.report().rowsByState().get("TIMED_OUT"));
        assertEquals(r.rowsPending(), store.getPending("recon-chunked-silent-chunk-0")
                + store.getPending("recon-chunked-silent-chunk-1") + store.getPending("recon-chunked-silent-chunk-2")
                + store.getPending("recon-chunked-silent-chunk-3") + store.getPending("recon-chunked-silent-chunk-4"));
    }
}
