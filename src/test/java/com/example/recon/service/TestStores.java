package com.example.recon.service;

import java.nio.file.Path;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class TestStores {

    public static ConfigurableApplicationContext open(Path db) {
        return new SpringApplicationBuilder(StoreOnlyTestConfig.class)
                .web(WebApplicationType.NONE)
                .run("--recon.db=" + db, "--spring.jpa.hibernate.ddl-auto=update");
    }

    private TestStores() {}
}
