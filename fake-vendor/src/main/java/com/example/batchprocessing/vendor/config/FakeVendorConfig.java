package com.example.batchprocessing.vendor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.batchprocessing.core.store.TxnStore;
import com.example.batchprocessing.core.vendor.FakeVendor;
import com.example.batchprocessing.core.vendor.FakeVendorSettings;
import com.example.batchprocessing.core.vendor.VendorCallbackListener;

import io.temporal.client.WorkflowClient;

@Configuration
@EnableConfigurationProperties(VendorProperties.class)
public class FakeVendorConfig {

    @Bean
    public VendorCallbackListener vendorCallbackListener(TxnStore store, WorkflowClient client) {
        return new VendorCallbackListener(store, client);
    }

    // Started and stopped with the Spring context
    @Bean(initMethod = "start", destroyMethod = "stop")
    public FakeVendor fakeVendor(TxnStore store, VendorCallbackListener callbacks, VendorProperties props) {
        FakeVendorSettings settings = props.toSettings(System.nanoTime());

        return new FakeVendor(store, callbacks, settings);
    }
}
