package com.example.recon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.example.recon.dto.FakeVendorSettings;
import com.example.recon.listener.VendorCallbackListener;
import com.example.recon.service.FakeVendor;
import com.example.recon.service.TxnStore;

@Configuration
@Profile("vendor")
public class VendorConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public FakeVendor fakeVendor(TxnStore store, VendorCallbackListener callbacks, ReconProperties props) {
        VendorProperties v = props.getVendor();
        FakeVendorSettings settings = new FakeVendorSettings(v.getMinDelaySeconds(),
                v.getMaxDelaySeconds(), v.getFailureRate(), v.getErrorCodes(), v.getPollMillis(),
                v.getPollBatch(), System.nanoTime());
        return new FakeVendor(store, callbacks, settings);
    }
}
