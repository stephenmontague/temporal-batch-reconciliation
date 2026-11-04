package com.example.batchprocessing.basicworkflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.batchprocessing.core.config.BatchProcessingProperties;
import com.example.batchprocessing.core.contract.WorkflowNames;
import com.example.batchprocessing.core.csv.CsvGenerator;
import com.example.batchprocessing.core.dto.FileInput;
import com.example.batchprocessing.core.dto.FileResult;
import com.example.batchprocessing.core.store.TxnState;
import com.example.batchprocessing.core.testsupport.InProcessVendorConfig;
import com.example.batchprocessing.core.testsupport.RealTimeTestServerConfig;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

// Boots the real worker application on the starter's embedded test server, with the fake vendor in the same context,
// and starts a file the way batch-processing-cli does: by workflow type name on the configured task queue.
// Fails if application.yml didn't register the workflow or the shared activities.
@SpringBootTest(classes = BasicWorkflowWorkerApplication.class)
@ActiveProfiles("test")
@Import({ RealTimeTestServerConfig.class, InProcessVendorConfig.class })
@DirtiesContext
class BasicWorkflowWorkerApplicationTest {

    private static final int ROWS = 2_000;

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("batch-processing.db", () -> tmp.resolve("batch-processing.db").toString());
        registry.add("batch-processing.report-dir", () -> tmp.resolve("reports").toString());
    }

    @Autowired
    WorkflowClient client;

    @Autowired
    BatchProcessingProperties props;

    @Test
    void processesAFileEndToEnd() throws Exception {
        Path file = tmp.resolve("spring-basic.csv");
        CsvGenerator.generate(file, ROWS, 0.01, 3);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId("basic-workflow-spring")
                .setTaskQueue(props.getBasicWorkflowTaskQueue())
                .build();
        WorkflowStub workflow = client.newUntypedWorkflowStub(WorkflowNames.BASIC_WORKFLOW, options);
        workflow.start(new FileInput(file.toString(), "spring-basic", 500, 4, 120, List.of("TIMEOUT")));

        FileResult result = workflow.getResultAsync(FileResult.class).get(90, TimeUnit.SECONDS);

        Map<String, Integer> byState = result.report().rowsByState();
        int answered = byState.getOrDefault(TxnState.COMPLETED, 0) + byState.getOrDefault(TxnState.FAILED, 0);

        assertEquals(FileResult.COMPLETED, result.outcome());
        assertTrue(result.allDoneBeforeDeadline());
        assertEquals(ROWS, result.totalRows());
        assertEquals(result.rowsPending(), answered);
    }
}
