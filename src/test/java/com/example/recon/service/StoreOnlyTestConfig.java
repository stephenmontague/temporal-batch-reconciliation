package com.example.recon.service;

import com.example.recon.config.PersistenceConfig;
import com.example.recon.config.ReconProperties;
import com.example.recon.entity.TxnRecord;
import com.example.recon.repository.TxnRepository;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@TestConfiguration
@ImportAutoConfiguration({HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
@EnableConfigurationProperties(ReconProperties.class)
@EntityScan(basePackageClasses = TxnRecord.class)
@EnableJpaRepositories(basePackageClasses = TxnRepository.class)
@Import({PersistenceConfig.class, TxnStore.class})
class StoreOnlyTestConfig {}
