package com.example.batchprocessing.cli;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.batchprocessing.cli.config.StartProperties;
import com.example.batchprocessing.cli.launch.CommandLauncher;
import com.example.batchprocessing.core.config.BatchProcessingProperties;

// Command-line tools: generate, start, intake, count-actions. A Temporal client only: it starts workflows and reads
// histories, and never opens the database, so the JPA auto-configuration batch-processing-core brings onto the classpath is off.
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class })
@EnableConfigurationProperties({ BatchProcessingProperties.class, StartProperties.class })
public class BatchProcessingCliApplication {

    public static void main(String[] args) {
        CommandLauncher.launch(BatchProcessingCliApplication.class, args);
    }
}
