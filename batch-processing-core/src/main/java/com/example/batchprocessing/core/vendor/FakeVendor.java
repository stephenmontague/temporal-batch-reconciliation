package com.example.batchprocessing.core.vendor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.DelayQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.batchprocessing.core.dto.OutboundMessage;
import com.example.batchprocessing.core.dto.VendorAnswer;
import com.example.batchprocessing.core.store.TxnStore;

// Stands in for the downstream vendor. Two threads:
//   downstream: takes queued rows from the outbound queue and schedules an answer for each, minDelay-maxDelay seconds out
//   callbacks:  hands answers to the callback listener as they come due
public class FakeVendor {

    private static final Logger log = LoggerFactory.getLogger(FakeVendor.class);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final TxnStore store;
    private final VendorCallbackListener callbacks;
    private final FakeVendorSettings settings;
    private final Random random;
    private final DelayQueue<ScheduledAnswer> inFlight = new DelayQueue<>();

    private volatile boolean running;
    private Thread downstreamThread;
    private Thread callbackThread;

    public FakeVendor(TxnStore store, VendorCallbackListener callbacks, FakeVendorSettings settings) {
        this.store = store;
        this.callbacks = callbacks;
        this.settings = settings;
        this.random = new Random(settings.seed());
    }

    public void start() {
        running = true;

        downstreamThread = new Thread(this::downstreamLoop, "fake-vendor-downstream");
        callbackThread = new Thread(this::deliveryLoop, "fake-vendor-callbacks");

        downstreamThread.start();
        callbackThread.start();

        log.info(
                "Fake vendor started: answers in {}-{}s, failure rate {}, error codes {}",
                settings.minDelaySeconds(),
                settings.maxDelaySeconds(),
                settings.failureRate(),
                settings.errorCodes());
    }

    public void stop() throws InterruptedException {
        running = false;

        downstreamThread.interrupt();
        callbackThread.interrupt();

        downstreamThread.join();
        callbackThread.join();
    }

    // ---- Downstream thread ----------------------------------------------------------------------

    private void downstreamLoop() {
        while (running) {
            try {
                acceptNextBatch();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.warn("Downstream poll failed, will retry", e);
                sleepQuietly(settings.pollMillis());
            }
        }
    }

    private void acceptNextBatch() throws InterruptedException {
        List<OutboundMessage> batch = store.takeOutbound(settings.pollBatch());
        long nowNanos = System.nanoTime();

        for (OutboundMessage message : batch) {
            ScheduledAnswer answer = scheduleAnswer(message, nowNanos);
            inFlight.add(answer);
        }

        if (!batch.isEmpty()) {
            log.info("Vendor accepted {} rows ({} awaiting answer)", batch.size(), inFlight.size());
        }

        boolean queueDrained = batch.size() < settings.pollBatch();

        if (queueDrained) {
            Thread.sleep(settings.pollMillis());
        }
    }

    private ScheduledAnswer scheduleAnswer(OutboundMessage message, long nowNanos) {
        double delaySeconds = randomDelaySeconds();
        String errorCode = randomErrorCodeOrNull();
        VendorAnswer answer = new VendorAnswer(message.txnId(), message.fileId(), errorCode);
        long dueNanos = nowNanos + (long) (delaySeconds * NANOS_PER_SECOND);

        return new ScheduledAnswer(answer, dueNanos);
    }

    private double randomDelaySeconds() {
        double range = settings.maxDelaySeconds() - settings.minDelaySeconds();

        return settings.minDelaySeconds() + random.nextDouble() * range;
    }

    private String randomErrorCodeOrNull() {
        boolean fails = random.nextDouble() < settings.failureRate();

        if (!fails) {
            return null;
        }

        List<String> errorCodes = settings.errorCodes();
        int pick = random.nextInt(errorCodes.size());

        return errorCodes.get(pick);
    }

    // ---- Callback thread ------------------------------------------------------------------------

    private void deliveryLoop() {
        while (running) {
            List<ScheduledAnswer> due;

            try {
                due = takeDueAnswers();
            } catch (InterruptedException e) {
                return;
            }

            deliver(due);
        }
    }

    // Blocks until at least one answer is due, then takes every answer that's due
    private List<ScheduledAnswer> takeDueAnswers() throws InterruptedException {
        List<ScheduledAnswer> due = new ArrayList<>();
        ScheduledAnswer first = inFlight.take();
        due.add(first);
        inFlight.drainTo(due);

        return due;
    }

    private void deliver(List<ScheduledAnswer> due) {
        List<VendorAnswer> answers = new ArrayList<>(due.size());

        for (ScheduledAnswer scheduled : due) {
            answers.add(scheduled.answer());
        }

        try {
            callbacks.onAnswers(answers);
        } catch (Exception e) {
            log.warn("Applying {} answers failed, re-queueing", answers.size(), e);
            inFlight.addAll(due);
            sleepQuietly(settings.pollMillis());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
