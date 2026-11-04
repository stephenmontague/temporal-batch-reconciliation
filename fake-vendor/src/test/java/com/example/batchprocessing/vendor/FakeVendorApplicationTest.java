package com.example.batchprocessing.vendor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.batchprocessing.core.vendor.FakeVendor;
import com.example.batchprocessing.vendor.config.VendorProperties;

// The vendor application wires up and reads its batch-processing.vendor settings
@SpringBootTest(classes = FakeVendorApplication.class)
@ActiveProfiles("test")
@DirtiesContext
class FakeVendorApplicationTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("batch-processing.db", () -> tmp.resolve("batch-processing.db").toString());
        registry.add("batch-processing.vendor.failure-rate", () -> "0.25");
    }

    @Autowired
    FakeVendor vendor;

    @Autowired
    VendorProperties props;

    @Test
    void contextStartsTheVendor() {
        assertNotNull(vendor);
        assertEquals(0.25, props.getFailureRate());
        assertEquals(30, props.getMaxDelaySeconds());
    }
}
