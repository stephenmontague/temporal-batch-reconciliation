package com.example.recon.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Random;

public final class CsvGenerator {

    private static final String[] MERCHANTS = {
            "Corner Grocery", "City Transit", "Book Nook", "Pizza Place", "Game Shop",
            "Movie House", "Coffee Cart", "Shoe Store", "Arcade", "School Store"
    };

    public static void generate(Path out, int rows, double invalidRate, long seed)
            throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        String tag = out.getFileName().toString().replace(".csv", "");
        Random random = new Random(seed);
        LocalDate base = LocalDate.of(2026, 10, 1);

        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("transaction_id,account_id,amount,currency,txn_date,merchant\n");

            for (int i = 0; i < rows; i++) {
                String txnId = String.format("%s-%08d", tag, i);
                String account = String.format("ACCT-%06d", random.nextInt(250_000));
                String amount = String.format("%d.%02d", random.nextInt(500), random.nextInt(100));
                String date = base.plusDays(random.nextInt(28)).toString();
                String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
                String currency = "USD";

                if (random.nextDouble() < invalidRate) {
                    switch (random.nextInt(3)) {
                        case 0 -> amount = "N/A";
                        case 1 -> currency = "usd$";
                        default -> date = "2026-13-45";
                    }
                }

                w.write(String.join(",", txnId, account, amount, currency, date, merchant));
                w.write('\n');
            }
        }
    }

    private CsvGenerator() {
    }
}
