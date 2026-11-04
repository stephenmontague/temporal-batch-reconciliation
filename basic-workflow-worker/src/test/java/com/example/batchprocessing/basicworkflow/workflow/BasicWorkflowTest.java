package com.example.batchprocessing.basicworkflow.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.batchprocessing.basicworkflow.activity.impl.ReconciliationActivitiesImpl;
import com.example.batchprocessing.basicworkflow.dto.RetryResult;
import com.example.batchprocessing.basicworkflow.processing.FailedRowRetrier;
import com.example.batchprocessing.basicworkflow.workflow.impl.BasicWorkflowImpl;
import com.example.batchprocessing.core.csv.CsvGenerator;
import com.example.batchprocessing.core.dto.FileInput;
import com.example.batchprocessing.core.dto.FileResult;
import com.example.batchprocessing.core.dto.RetryOutcome;
import com.example.batchprocessing.core.store.TxnState;
import com.example.batchprocessing.core.store.TxnStore;
import com.example.batchprocessing.core.testsupport.ActivityTypeCounts;
import com.example.batchprocessing.core.testsupport.TestStores;
import com.example.batchprocessing.core.testsupport.TestVendors;
import com.example.batchprocessing.core.vendor.FakeVendor;
import com.example.batchprocessing.core.vendor.FakeVendorSettings;

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;

class BasicWorkflowTest {

    private static final String TASK_QUEUE = "basic-workflow-test";
    private static final int ROWS = 2_500;
    private static final int BATCH_SIZE = 500;
    private static final int BATCHES = ROWS / BATCH_SIZE;
    private static final int MAX_PARALLEL = 3;
    private static final List<String> NO_RETRIES = List.of();
    private static final List<String> RETRY_TIMEOUTS = List.of("TIMEOUT");

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

    @Test
    void completesWhenVendorAnswersEveryRow() throws Exception {
        TxnStore store = startEnv(false);
        startVendor(store, 0.05);
        Path file = generate("happy");

        FileResult result = run("basic-workflow-happy", file, 120, NO_RETRIES);

        Map<String, Integer> byState = result.report().rowsByState();
        int answered = byState.getOrDefault(TxnState.COMPLETED, 0) + byState.getOrDefault(TxnState.FAILED, 0);
        int reportLines = Files.readAllLines(Path.of(result.report().reportPath())).size();

        assertEquals(FileResult.COMPLETED, result.outcome());
        assertTrue(result.allDoneBeforeDeadline());
        assertEquals(ROWS, result.totalRows());
        assertEquals(0, result.rowsTimedOut());
        assertEquals(result.rowsPending(), result.rowsSubmitted());
        assertEquals(result.rowsPending(), answered);
        assertEquals(result.rowsInvalid(), byState.getOrDefault(TxnState.INVALID, 0));
        assertEquals(ROWS, answered + result.rowsInvalid());
        assertTrue(byState.getOrDefault(TxnState.FAILED, 0) > 0, "expected some vendor failures");
        assertEquals(ROWS + 1, reportLines);
        assertEquals(RetryOutcome.notNeeded(), result.retry());

        WorkflowClient client = env.getWorkflowClient();
        WorkflowReplayer.replayWorkflowExecution(client.fetchHistory("basic-workflow-happy"), BasicWorkflowImpl.class);

        // Seed and submit run once per batch; the counter steps run once for the whole file
        Map<String, Integer> expectedActivities = Map.of(
                "CountRows", 1,
                "SeedBatch", BATCHES,
                "StartCounter", 1,
                "SubmitBatch", BATCHES,
                "GetPending", 1,
                "MarkTimeouts", 1,
                "WriteReport", 1);
        assertEquals(expectedActivities, ActivityTypeCounts.of(client, "basic-workflow-happy"));

        // One allDone signal for the whole file
        List<HistoryEvent> events = client.fetchHistory("basic-workflow-happy").getEvents();
        long signals = events.stream().filter(event -> event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED).count();

        assertEquals(1, signals);
    }

    @Test
    void deadlineMarksStragglersTimedOut() throws Exception {
        TxnStore store = startEnv(true);
        Path file = generate("nobody-answers");

        FileResult result = run("basic-workflow-nobody-answers", file, 7200, RETRY_TIMEOUTS);

        assertEquals(FileResult.TIMED_OUT, result.outcome());
        assertFalse(result.allDoneBeforeDeadline());
        assertEquals(result.rowsPending(), result.rowsTimedOut());
        assertEquals(result.rowsPending(), result.report().rowsByState().get(TxnState.TIMED_OUT));
        assertEquals(result.rowsPending(), store.getPending("nobody-answers"));
        assertEquals(RetryOutcome.notNeeded(), result.retry());
    }

    @Test
    void retriesFailedRowsWithBackoffUntilTheyGoThrough() throws Exception {
        AtomicInteger retryAttempts = new AtomicInteger();
        startEnv(true, store -> new FlakyRetries(store, tmp.resolve("reports"), retryAttempts, 2));
        Path file = generate("flaky");

        FileResult result = run("basic-workflow-flaky", file, 7200, RETRY_TIMEOUTS);

        assertEquals(new RetryOutcome(RetryOutcome.SUCCEEDED, 3), result.retry());
        assertEquals(3, retryAttempts.get());
    }

    @Test
    void givesUpAfterTheLastAttemptAndStillWritesTheReport() throws Exception {
        AtomicInteger retryAttempts = new AtomicInteger();
        startEnv(true, store -> new FlakyRetries(store, tmp.resolve("reports"), retryAttempts, Integer.MAX_VALUE));
        Path file = generate("hopeless");

        FileResult result = run("basic-workflow-hopeless", file, 7200, RETRY_TIMEOUTS);

        WorkflowClient client = env.getWorkflowClient();
        Map<String, Integer> activityCounts = ActivityTypeCounts.of(client, "basic-workflow-hopeless");
        WorkflowReplayer.replayWorkflowExecution(client.fetchHistory("basic-workflow-hopeless"), BasicWorkflowImpl.class);

        assertEquals(new RetryOutcome(RetryOutcome.GAVE_UP, 4), result.retry());
        assertEquals(4, retryAttempts.get());
        assertEquals(1, activityCounts.get("RetryFailedRows"));
        assertEquals(2, activityCounts.get("MarkTimeouts"));
        assertTrue(Files.exists(Path.of(result.report().reportPath())));
    }

    @Test
    void rerunOfFinishedFileClosesImmediatelyViaCounterGuard() throws Exception {
        TxnStore store = startEnv(false);
        startVendor(store, 0.0);
        Path file = generate("rerun");

        FileResult first = run("basic-workflow-rerun", file, 120, RETRY_TIMEOUTS);
        assertEquals(FileResult.COMPLETED, first.outcome());

        vendor.stop();
        vendor = null;

        long startedAt = System.currentTimeMillis();
        FileResult second = run("basic-workflow-rerun-2", file, 120, RETRY_TIMEOUTS);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals(FileResult.COMPLETED, second.outcome());
        assertTrue(second.allDoneBeforeDeadline());
        assertEquals(0, second.rowsSubmitted());
        assertEquals(RetryOutcome.notNeeded(), second.retry());
        assertTrue(elapsedMillis < 30_000, "took " + elapsedMillis + " ms");
    }

    private TxnStore startEnv(boolean timeSkipping) {
        return startEnv(timeSkipping, store -> new ReconciliationActivitiesImpl(store, tmp.resolve("reports")));
    }

    private TxnStore startEnv(boolean timeSkipping, Function<TxnStore, ReconciliationActivitiesImpl> activitiesFor) {
        TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().setUseTimeskipping(timeSkipping).build();
        env = TestWorkflowEnvironment.newInstance(options);
        storeContext = TestStores.open(tmp.resolve("batch-processing.db"));
        TxnStore store = TestStores.storeOf(storeContext);

        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(BasicWorkflowImpl.class);
        worker.registerActivitiesImplementations(activitiesFor.apply(store));
        env.start();

        return store;
    }

    private void startVendor(TxnStore store, double failureRate) {
        FakeVendorSettings settings = TestVendors.fastSettings(0.5, failureRate, 7);
        vendor = TestVendors.start(store, env.getWorkflowClient(), settings);
    }

    private Path generate(String name) throws Exception {
        Path file = tmp.resolve(name + ".csv");
        CsvGenerator.generate(file, ROWS, 0.01, 42);

        return file;
    }

    private FileResult run(String workflowId, Path file, long deadlineSeconds, List<String> retryCodes) throws Exception {
        WorkflowClient client = env.getWorkflowClient();
        WorkflowOptions options = WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE).build();
        BasicWorkflow workflow = client.newWorkflowStub(BasicWorkflow.class, options);

        String fileId = file.getFileName().toString().replace(".csv", "");
        FileInput input = new FileInput(file.toString(), fileId, BATCH_SIZE, MAX_PARALLEL, deadlineSeconds, retryCodes);
        WorkflowClient.start(workflow::reconcile, input);

        return client.newUntypedWorkflowStub(workflowId).getResultAsync(FileResult.class).get(90, TimeUnit.SECONDS);
    }

    // The real activities, except retryFailedRows: it fails its first `failures` attempts as if rows failed again, then succeeds.
    // Lets a test drive the retry policy and the workflow's catch without a vendor.
    private static class FlakyRetries extends ReconciliationActivitiesImpl {

        private final AtomicInteger attempts;
        private final int failures;

        FlakyRetries(TxnStore store, Path reportDir, AtomicInteger attempts, int failures) {
            super(store, reportDir);
            this.attempts = attempts;
            this.failures = failures;
        }

        @Override
        public RetryResult retryFailedRows(String fileId, List<String> retryableErrorCodes) {
            int attempt = attempts.incrementAndGet();

            if (attempt <= failures) {
                throw ApplicationFailure.newFailure("rows failed again (test)", FailedRowRetrier.ROWS_STILL_FAILING);
            }

            return new RetryResult(attempt, 5);
        }
    }
}
