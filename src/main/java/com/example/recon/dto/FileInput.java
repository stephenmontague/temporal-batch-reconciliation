package com.example.recon.dto;

public record FileInput(String fileRef, String fileId, int batchSize, int maxParallel,
        long deadlineSeconds) {
}
