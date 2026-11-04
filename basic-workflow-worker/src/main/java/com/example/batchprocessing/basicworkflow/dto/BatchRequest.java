package com.example.batchprocessing.basicworkflow.dto;

// One slice of the file: `size` data rows starting at data row `offset`.
public record BatchRequest(String fileRef, String fileId, int offset, int size) {
}
