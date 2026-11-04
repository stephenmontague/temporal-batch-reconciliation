package com.example.recon.dto;

public record StartSettings(int batchSize, int maxParallel, long deadlineSeconds, String design) {

    public static final String FILE_DESIGN = "file";
    public static final String CHUNK_DESIGN = "chunk";

    public StartSettings {
        if (!FILE_DESIGN.equals(design) && !CHUNK_DESIGN.equals(design)) {
            throw new IllegalArgumentException("design must be '" + FILE_DESIGN + "' or '"
                    + CHUNK_DESIGN + "', not '" + design + "'");
        }
    }
}
