package com.example.recon.dto;

public record ChunkInput(String fileRef, String fileId, int chunkIndex, int offset, int size,
        long deadlineAtMillis) {
}
