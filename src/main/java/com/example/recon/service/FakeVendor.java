package com.example.recon.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.DelayQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.recon.dto.FakeVendorSettings;
import com.example.recon.dto.OutboundMessage;
import com.example.recon.dto.VendorAnswer;
import com.example.recon.listener.VendorCallbackListener;

public class FakeVendor {

    private static final Logger log = LoggerFactory.getLogger(FakeVendor.class);

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

        log.info("Fake vendor started: answers in {}-{}s, failure rate {}, error codes {}",
                settings.minDelaySeconds(), settings.maxDelaySeconds(), settings.failureRate(),
                settings.errorCodes());
    }

    public void stop() throws InterruptedException {
        running = false;
        downstreamThread.interrupt();
        callbackThread.interrupt();
        downstreamThread.join();
        callbackThread.join();
    }

    private void downstreamLoop() {
        while (running) {
            try {
                List<OutboundMessage> batch = store.takeOutbound(settings.pollBatch());
                long now = System.nanoTime();

                for (OutboundMessage m : batch) {
                    double delay = settings.minDelaySeconds()
                            + random.nextDouble() * (settings.maxDelaySeconds() - settings.minDelaySeconds());

                    String error = random.nextDouble() < settings.failureRate()
                            ? settings.errorCodes().get(random.nextInt(settings.errorCodes().size()))
                            : null;

                    inFlight.add(new ScheduledAnswer(
                            new VendorAnswer(m.txnId(), m.fileId(), error),
                            now + (long) (delay * 1_000_000_000L)));
                }

                if (!batch.isEmpty()) {
                    log.info("Vendor accepted {} rows ({} awaiting answer)", batch.size(), inFlight.size());
                }

                if (batch.size() < settings.pollBatch()) {
                    Thread.sleep(settings.pollMillis());
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.warn("Downstream poll failed, will retry", e);
                sleepQuietly(settings.pollMillis());
            }
        }
    }

    private void deliveryLoop() {
        while (running) {
            List<ScheduledAnswer> due = new ArrayList<>();
            try {
                due.add(inFlight.take());
            } catch (InterruptedException e) {
                return;
            }
            inFlight.drainTo(due);
            List<VendorAnswer> answers = new ArrayList<>(due.size());
            for (ScheduledAnswer s : due) {
                answers.add(s.answer);
            }
            try {
                callbacks.onAnswers(answers);
            } catch (Exception e) {
                log.warn("Applying {} answers failed, re-queueing", answers.size(), e);
                inFlight.addAll(due);
                sleepQuietly(settings.pollMillis());
            }
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
