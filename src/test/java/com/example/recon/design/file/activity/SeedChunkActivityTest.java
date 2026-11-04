package com.example.recon.design.file.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.SeedProgress;
import com.example.recon.dto.SeedResult;
import com.example.recon.service.CsvGenerator;
import com.example.recon.service.TestStores;
import com.example.recon.service.TxnStore;
import io.temporal.testing.TestActivityEnvironment;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

class SeedChunkActivityTest {

    @TempDir
    Path tmp;

    private TestActivityEnvironment env;
    private ConfigurableApplicationContext storeContext;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
        if (storeContext != null) {
            storeContext.close();
        }
    }

    private TxnStore openStore() {
        storeContext = TestStores.open(tmp.resolve("recon.db"));
        return storeContext.getBean(TxnStore.class);
    }

    private ReconciliationActivities activities(TxnStore store) {
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(
                new ReconciliationActivitiesImpl(store, tmp.resolve("reports")));
        return env.newActivityStub(ReconciliationActivities.class);
    }

    @Test
    void seedingTwiceWritesEachRowOnce() throws Exception {
        Path file = tmp.resolve("twice.csv");
        CsvGenerator.generate(file, 1_000, 0.0, 1);
        TxnStore store = openStore();
        ReconciliationActivities acts = activities(store);
        ChunkRequest chunk = new ChunkRequest(file.toString(), "twice", 0, 1_000);

        SeedResult first = acts.seedChunk(chunk);
        SeedResult second = acts.seedChunk(chunk);

        assertEquals(1_000, first.inserted());
        assertEquals(1_000, second.read());
        assertEquals(0, second.inserted());
        assertEquals(1_000, acts.setCounter("twice"));
    }

    @Test
    void retryResumesFromHeartbeatedOffset() throws Exception {
        Path file = tmp.resolve("resume.csv");
        CsvGenerator.generate(file, 1_000, 0.0, 1);
        TxnStore store = openStore();
        ReconciliationActivities acts = activities(store);

        env.setHeartbeatDetails(new SeedProgress(600, 600, 600, 0));

        SeedResult r = acts.seedChunk(new ChunkRequest(file.toString(), "resume", 0, 1_000));

        assertEquals(1_000, r.read());
        assertEquals(1_000, r.inserted());
        assertEquals(400, store.countByState("resume").get(TxnStore.SEEDED));
    }
}
