package com.example.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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

import com.example.recon.config.ReconProperties;
import com.example.recon.dto.FileResult;
import com.example.recon.dto.StartSettings;
import com.example.recon.service.ActionCounter;
import com.example.recon.service.CsvGenerator;
import com.example.recon.service.FileIntake;

import io.temporal.client.WorkflowClient;

@SpringBootTest(classes = ReconApplication.class)
@ActiveProfiles({ "worker", "vendor", "test" })
@Import(RealTimeTestServerConfig.class)
@DirtiesContext
class ReconApplicationSpringTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("recon.db", () -> tmp.resolve("recon.db").toString());
        registry.add("recon.report()-dir", () -> tmp.resolve("reports").toString());
    }

    @Autowired
    WorkflowClient client;

    @Autowired
    ReconProperties props;

    @Test
    void fileDesignProcessesAFileEndToEnd() throws Exception {
        runEndToEnd("spring-file", StartSettings.FILE_DESIGN);
    }

    @Test
    void chunkDesignProcessesAFileEndToEnd() throws Exception {
        FileResult r = runEndToEnd("spring-chunk", StartSettings.CHUNK_DESIGN);
        assertEquals(4, new ActionCounter(client).count("recon-spring-chunk").childStarts());
        assertEquals(r.rowsPending(), r.rowsSubmitted());
    }

    private FileResult runEndToEnd(String name, String design) throws Exception {
        Path file = tmp.resolve(name + ".csv");
        CsvGenerator.generate(file, 2_000, 0.01, 3);

        String workflowId = new FileIntake(client, props.getFileTaskQueue(), props.getChunkTaskQueue())
                .submit(file, new StartSettings(500, 4, 120, design));
        FileResult r = client.newUntypedWorkflowStub(workflowId)
                .getResultAsync(FileResult.class)
                .get(90, TimeUnit.SECONDS);

        assertEquals("COMPLETED", r.outcome());
        assertTrue(r.allDoneBeforeDeadline());
        assertEquals(2_000, r.totalRows());
        int answered = r.report().rowsByState().getOrDefault("COMPLETED", 0)
                + r.report().rowsByState().getOrDefault("FAILED", 0);
        assertEquals(r.rowsPending(), answered);
        return r;
    }
}
