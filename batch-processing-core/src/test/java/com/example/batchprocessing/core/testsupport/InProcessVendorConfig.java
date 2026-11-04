package com.example.batchprocessing.core.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.example.batchprocessing.core.store.TxnStore;
import com.example.batchprocessing.core.vendor.FakeVendor;
import com.example.batchprocessing.core.vendor.FakeVendorSettings;
import com.example.batchprocessing.core.vendor.VendorCallbackListener;

import io.temporal.client.WorkflowClient;

// Runs the fake vendor inside a worker application's Spring test, so one context can process a file end to end
@TestConfiguration
public class InProcessVendorConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public FakeVendor inProcessVendor(TxnStore store, WorkflowClient client) {
        FakeVendorSettings settings = TestVendors.fastSettings(0.5, 0.02, 5);
        VendorCallbackListener callbacks = new VendorCallbackListener(store, client);

        return new FakeVendor(store, callbacks, settings);
    }
}
