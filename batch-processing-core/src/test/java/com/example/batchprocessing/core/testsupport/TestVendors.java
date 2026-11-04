package com.example.batchprocessing.core.testsupport;

import java.util.List;

import com.example.batchprocessing.core.store.TxnStore;
import com.example.batchprocessing.core.vendor.FakeVendor;
import com.example.batchprocessing.core.vendor.FakeVendorSettings;
import com.example.batchprocessing.core.vendor.VendorCallbackListener;

import io.temporal.client.WorkflowClient;

// A fake vendor that answers quickly, for tests
public final class TestVendors {

    public static final List<String> ERROR_CODES = List.of("ACCOUNT_LOCKED", "TIMEOUT");

    private static final long POLL_MILLIS = 100;
    private static final int POLL_BATCH = 2000;

    public static FakeVendorSettings fastSettings(double maxDelaySeconds, double failureRate, long seed) {
        return new FakeVendorSettings(0, maxDelaySeconds, failureRate, ERROR_CODES, POLL_MILLIS, POLL_BATCH, seed);
    }

    public static FakeVendor start(TxnStore store, WorkflowClient client, FakeVendorSettings settings) {
        VendorCallbackListener callbacks = new VendorCallbackListener(store, client);
        FakeVendor vendor = new FakeVendor(store, callbacks, settings);
        vendor.start();

        return vendor;
    }

    private TestVendors() {}
}
