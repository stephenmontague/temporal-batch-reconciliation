package com.example.batchprocessing.basicworkflow.dto;

// Heartbeat details while seeding a batch, so a retry resumes at nextOffset instead of starting over.
public record SeedProgress(int nextOffset, int read, int inserted, int invalid) {
}
