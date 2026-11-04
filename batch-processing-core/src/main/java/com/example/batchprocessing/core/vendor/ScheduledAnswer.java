package com.example.batchprocessing.core.vendor;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import com.example.batchprocessing.core.dto.VendorAnswer;

// An answer the fake vendor will deliver once System.nanoTime() reaches dueNanos
record ScheduledAnswer(VendorAnswer answer, long dueNanos) implements Delayed {

    @Override
    public long getDelay(TimeUnit unit) {
        long remainingNanos = dueNanos - System.nanoTime();

        return unit.convert(remainingNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        ScheduledAnswer otherAnswer = (ScheduledAnswer) other;

        return Long.compare(dueNanos, otherAnswer.dueNanos);
    }
}
