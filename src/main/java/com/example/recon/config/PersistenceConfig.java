package com.example.recon.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class PersistenceConfig {

    @Bean
    public DataSource dataSource(ReconProperties props) throws IOException {
        Path db = props.getDb().toAbsolutePath();

        Files.createDirectories(db.getParent());

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sqlite:" + db
                + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=30000&transaction_mode=IMMEDIATE");
        ds.setMaximumPoolSize(10);

        return ds;
    }
}
