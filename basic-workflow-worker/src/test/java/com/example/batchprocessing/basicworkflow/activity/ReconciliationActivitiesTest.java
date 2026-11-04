package com.example.batchprocessing.basicworkflow.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.batchprocessing.basicworkflow.activity.impl.ReconciliationActivitiesImpl;
import com.example.batchprocessing.basicworkflow.dto.BatchRequest;
import com.example.batchprocessing.basicworkflow.dto.RetryResult;
import com.example.batchprocessing.basicworkflow.dto.SeedProgress;
import com.example.batchprocessing.basicworkflow.dto.SeedResult;
import com.example.batchprocessing.basicworkflow.processing.FailedRowRetrier;
import com.example.batchprocessing.core.csv.CsvGenerator;
import com.example.batchprocessing.core.dto.VendorAnswer;
import com.example.batchprocessing.core.store.TxnState;
import com.example.batchprocessing.core.store.TxnStore;
import com.example.batchprocessing.core.testsupport.TestStores;
import com.example.batchprocessing.core.vendor.FakeVendor;
import com.example.batchprocessing.core.vendor.FakeVendorSettings;
import com.example.batchprocessing.core.vendor.VendorCallbackListener;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;

class ReconciliationActivitiesTest {

    private static final int ROWS = 1_000;
    private static final int TIMEOUT_ROWS = 10;
    private static final int LOCKED_ROWS = 5;
    private static final List<String> RETRY_TIMEOUTS = List.of("TIMEOUT");

    @TempDir
    Path tmp;

    private TestActivityEnvironment env;
    private ConfigurableApplicationContext storeContext;
    private FakeVendor vendor;

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
    void seedingTwiceWritesEachRowOnce() throws Exception {
        Path file = tmp.resolve("twice.csv");
        CsvGenerator.generate(file, 1_000, 0.0, 1);
        TxnStore store = openStore();
        ReconciliationActivities activities = activities(store);
        BatchRequest batch = new BatchRequest(file.toString(), "twice", 0, 1_000);

        SeedResult first = activities.seedBatch(batch, "twice");
        SeedResult second = activities.seedBatch(batch, "twice");

        assertEquals(1_000, first.inserted());
        assertEquals(1_000, second.read());
        assertEquals(0, second.inserted());
        assertEquals(1_000, activities.startCounter("twice"));
    }

    @Test
    void retryResumesFromHeartbeatedOffset() throws Exception {
        Path file = tmp.resolve("resume.csv");
        CsvGenerator.generate(file, 1_000, 0.0, 1);
        TxnStore store = openStore();
        ReconciliationActivities activities = activities(store);
        env.setHeartbeatDetails(new SeedProgress(600, 600, 600, 0));

        SeedResult result = activities.seedBatch(new BatchRequest(file.toString(), "resume", 0, 1_000), "resume");

        Map<String, Integer> byState = store.countByStateForFile("resume");
        assertEquals(1_000, result.read());
        assertEquals(1_000, result.inserted());
        assertEquals(400, byState.get(TxnState.SEEDED));
    }

    @Test
    void retrySendsOnlyRowsWithARetryableCode() throws Exception {
        TxnStore store = openStore();
        ReconciliationActivities activities = activities(store);
        firstPassWithFailures(store, activities, "retry");

        vendor = startVendor(store, 0.0);

        RetryResult result = activities.retryFailedRows("retry", RETRY_TIMEOUTS);

        Map<String, Integer> byState = store.countByStateForFile("retry");
        Map<String, Integer> failures = store.countFailuresForFile("retry");

        assertEquals(TIMEOUT_ROWS, result.rowsSent());
        assertEquals(ROWS - LOCKED_ROWS, byState.get(TxnState.COMPLETED));
        assertEquals(Map.of("ACCOUNT_LOCKED", LOCKED_ROWS), failures);
    }

    @Test
    void retryThrowsWhenRowsFailAgain() throws Exception {
        TxnStore store = openStore();
        ReconciliationActivities activities = activities(store);
        firstPassWithFailures(store, activities, "again");

        vendor = startVendor(store, 1.0);

        ActivityFailure failure = assertThrows(ActivityFailure.class, () -> activities.retryFailedRows("again", RETRY_TIMEOUTS));

        ApplicationFailure cause = (ApplicationFailure) failure.getCause();
        Map<String, Integer> failures = store.countFailuresForFile("again");

        assertEquals(FailedRowRetrier.ROWS_STILL_FAILING, cause.getType());
        assertEquals(Map.of("ACCOUNT_LOCKED", LOCKED_ROWS, "TIMEOUT", TIMEOUT_ROWS), failures);
    }

    @Test
    void retryWithNothingToSendReturnsAtOnce() throws Exception {
        TxnStore store = openStore();
        ReconciliationActivities activities = activities(store);
        firstPassWithFailures(store, activities, "locked-only");

        RetryResult result = activities.retryFailedRows("locked-only", List.of("SOME_OTHER_CODE"));

        assertEquals(0, result.rowsSent());
    }

    // Seeds and submits the file, then answers every row by hand: the first TIMEOUT_ROWS fail with TIMEOUT,
    // the next LOCKED_ROWS with ACCOUNT_LOCKED, and the rest succeed
    private void firstPassWithFailures(TxnStore store, ReconciliationActivities activities, String fileId) throws Exception {
        Path file = tmp.resolve(fileId + ".csv");
        CsvGenerator.generate(file, ROWS, 0.0, 1);

        BatchRequest batch = new BatchRequest(file.toString(), fileId, 0, ROWS);
        activities.seedBatch(batch, fileId);
        activities.startCounter(fileId);
        activities.submitBatch(batch);

        store.takeOutbound(ROWS);

        List<VendorAnswer> answers = new ArrayList<>();

        for (int index = 0; index < ROWS; index++) {
            String txnId = String.format("%s-%08d", fileId, index);
            String errorCode = errorCodeFor(index);

            answers.add(new VendorAnswer(txnId, fileId, errorCode));
        }

        store.applyAnswers(answers);
    }

    private static String errorCodeFor(int index) {
        if (index < TIMEOUT_ROWS) {
            return "TIMEOUT";
        }

        if (index < TIMEOUT_ROWS + LOCKED_ROWS) {
            return "ACCOUNT_LOCKED";
        }

        return null;
    }

    // Answers within 0.2 s; failureRate of the answers fail with TIMEOUT. Resent rows never reach zero on a counter,
    // so the callback listener never signals and needs no WorkflowClient.
    private static FakeVendor startVendor(TxnStore store, double failureRate) {
        FakeVendorSettings settings = new FakeVendorSettings(0, 0.2, failureRate, List.of("TIMEOUT"), 50, 2_000, 1);
        VendorCallbackListener callbacks = new VendorCallbackListener(store, null);
        FakeVendor fakeVendor = new FakeVendor(store, callbacks, settings);

        fakeVendor.start();

        return fakeVendor;
    }

    private TxnStore openStore() {
        storeContext = TestStores.open(tmp.resolve("batch-processing.db"));

        return TestStores.storeOf(storeContext);
    }

    private ReconciliationActivities activities(TxnStore store) {
        ReconciliationActivitiesImpl implementation = new ReconciliationActivitiesImpl(store, tmp.resolve("reports"));
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(implementation);

        // One attempt per call, so a test sees each failure instead of the retry policy hiding it
        ActivityOptions options = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .setHeartbeatTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build();

        return env.newActivityStub(ReconciliationActivities.class, options);
    }
}
