package com.example.batchprocessing.core.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Random;

// Writes a fake settlement CSV. The same seed always produces the same file.
public final class CsvGenerator {

    private static final String HEADER = "transaction_id,account_id,amount,currency,txn_date,merchant\n";
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 1);
    private static final int DAYS = 28;
    private static final int ACCOUNTS = 250_000;
    private static final int MAX_DOLLARS = 500;

    private static final String[] MERCHANTS = {
            "Corner Grocery", "City Transit", "Book Nook", "Pizza Place", "Game Shop",
            "Movie House", "Coffee Cart", "Shoe Store", "Arcade", "School Store"
    };

    public static void generate(Path out, int rows, double invalidRate, long seed) throws IOException {
        createParentDirectories(out);

        String tag = out.getFileName().toString().replace(".csv", "");
        Random random = new Random(seed);

        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write(HEADER);

            for (int index = 0; index < rows; index++) {
                String line = randomRow(tag, index, random, invalidRate);
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    // The order of the random calls is fixed, so a seed keeps producing the same file
    private static String randomRow(String tag, int index, Random random, double invalidRate) {
        String txnId = String.format("%s-%08d", tag, index);
        String account = String.format("ACCT-%06d", random.nextInt(ACCOUNTS));
        String amount = String.format("%d.%02d", random.nextInt(MAX_DOLLARS), random.nextInt(100));
        String date = FIRST_DAY.plusDays(random.nextInt(DAYS)).toString();
        String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
        String currency = "USD";

        boolean makeInvalid = random.nextDouble() < invalidRate;

        if (makeInvalid) {
            int defect = random.nextInt(3);

            switch (defect) {
                case 0 -> amount = "N/A";
                case 1 -> currency = "usd$";
                default -> date = "2026-13-45";
            }
        }

        return String.join(",", txnId, account, amount, currency, date, merchant);
    }

    private static void createParentDirectories(Path out) throws IOException {
        Path parent = out.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private CsvGenerator() {}
}
