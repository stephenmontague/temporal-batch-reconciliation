package com.example.batchprocessing.core.testsupport;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import com.example.batchprocessing.core.config.PersistenceConfig;

// Just enough Spring to get a TxnStore: the DataSource, Hibernate, and transactions. No Temporal.
@TestConfiguration
@ImportAutoConfiguration({ HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class })
@Import(PersistenceConfig.class)
public class StoreOnlyTestConfig {
}
