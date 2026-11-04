package com.example.batchprocessing.core.testsupport;

import java.nio.file.Path;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.batchprocessing.core.store.TxnStore;

// Opens a store-only Spring context on its own database file, for tests that drive Temporal's test environment directly.
public final class TestStores {

    public static ConfigurableApplicationContext open(Path db) {
        return new SpringApplicationBuilder(StoreOnlyTestConfig.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--batch-processing.db=" + db,
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect");
    }

    public static TxnStore storeOf(ConfigurableApplicationContext context) {
        return context.getBean(TxnStore.class);
    }

    private TestStores() {}
}
