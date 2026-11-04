package com.example.batchprocessing.core.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.batchprocessing.core.store.entity.PendingCounter;

public interface PendingCounterRepository extends JpaRepository<PendingCounter, String> {
}
