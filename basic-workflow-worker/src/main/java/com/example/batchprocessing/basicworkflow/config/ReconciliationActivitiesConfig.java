package com.example.batchprocessing.basicworkflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.example.batchprocessing.basicworkflow.activity.impl.ReconciliationActivitiesImpl;
import com.example.batchprocessing.core.config.BatchProcessingProperties;
import com.example.batchprocessing.core.config.PersistenceConfig;
import com.example.batchprocessing.core.store.TxnStore;

// The activity bean. The worker @Imports this and lists it by name in its application.yml:
//   spring.temporal.workers[0].activity-beans: [reconciliationActivities]
@Configuration
@Import(PersistenceConfig.class)
public class ReconciliationActivitiesConfig {

    public static final String BEAN_NAME = "reconciliationActivities";

    @Bean(BEAN_NAME)
    public ReconciliationActivitiesImpl reconciliationActivities(TxnStore store, BatchProcessingProperties props) {
        return new ReconciliationActivitiesImpl(store, props.getReportDir());
    }
}
