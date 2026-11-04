package com.example.batchprocessing.core.store.repository;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.batchprocessing.core.store.entity.OutboundQueueEntry;

public interface OutboundQueueRepository extends JpaRepository<OutboundQueueEntry, String> {

    List<OutboundQueueEntry> findByStatus(String status, Limit limit);
}
