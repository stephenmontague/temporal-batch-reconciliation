package com.example.recon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.recon.entity.PendingCounter;

public interface PendingCounterRepository extends JpaRepository<PendingCounter, String> {
}
