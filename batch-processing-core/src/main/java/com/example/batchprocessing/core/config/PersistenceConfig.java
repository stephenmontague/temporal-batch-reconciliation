package com.example.batchprocessing.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.batchprocessing.core.store.TxnStore;
import com.example.batchprocessing.core.store.entity.TxnRecord;
import com.example.batchprocessing.core.store.repository.TxnRepository;
import com.zaxxer.hikari.HikariDataSource;

// Everything an application needs to read and write the shared SQLite database.
// Apps that touch the database (the worker and the fake vendor) @Import this class.
@Configuration
@EnableConfigurationProperties(BatchProcessingProperties.class)
@EntityScan(basePackageClasses = TxnRecord.class)
@EnableJpaRepositories(basePackageClasses = TxnRepository.class)
@Import(TxnStore.class)
public class PersistenceConfig {

    // WAL + busy timeout + BEGIN IMMEDIATE, because the worker and vendor processes write to the same file
    private static final String SQLITE_OPTIONS = "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=30000&transaction_mode=IMMEDIATE";
    private static final int MAX_POOL_SIZE = 10;

    @Bean
    public DataSource dataSource(BatchProcessingProperties props) throws IOException {
        Path db = props.getDb().toAbsolutePath();
        Files.createDirectories(db.getParent());

        String jdbcUrl = "jdbc:sqlite:" + db + SQLITE_OPTIONS;

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setMaximumPoolSize(MAX_POOL_SIZE);

        return dataSource;
    }
}
