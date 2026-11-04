package com.example.recon.service;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import com.example.recon.dto.VendorAnswer;

final class ScheduledAnswer implements Delayed {
    final VendorAnswer answer;
    final long dueNanos;

    ScheduledAnswer(VendorAnswer answer, long dueNanos) {
        this.answer = answer;
        this.dueNanos = dueNanos;
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(dueNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        return Long.compare(dueNanos, ((ScheduledAnswer) o).dueNanos);
    }
}
