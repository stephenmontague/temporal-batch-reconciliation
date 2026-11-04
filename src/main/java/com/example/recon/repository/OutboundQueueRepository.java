package com.example.recon.repository;

import com.example.recon.entity.OutboundQueueEntry;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboundQueueRepository extends JpaRepository<OutboundQueueEntry, String> {

    List<OutboundQueueEntry> findByStatus(String status, Limit limit);
}
