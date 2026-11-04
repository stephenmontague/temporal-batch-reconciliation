package com.example.batchprocessing.vendor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.example.batchprocessing.core.config.PersistenceConfig;

// The fake downstream vendor plus its callback listener. Not a Temporal worker: it takes rows off the outbound queue,
// answers them a few seconds later, and signals allDone to the workflow that owns a counter when it reaches zero.
@SpringBootApplication
@Import(PersistenceConfig.class)
public class FakeVendorApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FakeVendorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setKeepAlive(true);
        app.run(args);
    }
}
