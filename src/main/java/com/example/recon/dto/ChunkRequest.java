package com.example.recon.dto;

public record ChunkRequest(String fileRef, String fileId, int offset, int size) {
}
