package com.example.batchprocessing.basicworkflow.processing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import com.example.batchprocessing.core.dto.ParsedRow;

// Parses and validates one settlement CSV data row: transaction_id,account_id,amount,currency,txn_date,merchant
public final class CsvRowParser {

    private static final int COLUMN_COUNT = 6;
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public static ParsedRow parse(String fileId, int lineNo, String line) {
        String[] fields = line.split(",", -1);
        String txnId = txnIdOf(fields, fileId, lineNo);

        if (fields.length != COLUMN_COUNT) {
            return ParsedRow.unreadable(txnId, lineNo, "BAD_COLUMN_COUNT");
        }

        String accountId = fields[1].trim();
        String amount = fields[2].trim();
        String currency = fields[3].trim();
        String txnDate = fields[4].trim();
        String merchant = fields[5].trim();

        Long amountCents = toCents(amount);
        String errorCode = firstProblem(accountId, amountCents, currency, txnDate);

        return new ParsedRow(
                txnId,
                lineNo,
                accountId,
                amountCents,
                currency,
                txnDate,
                merchant,
                errorCode);
    }

    // A row with no transaction ID still needs a stable, unique key
    private static String txnIdOf(String[] fields, String fileId, int lineNo) {
        boolean hasTxnId = fields.length > 0 && !fields[0].isBlank();

        if (hasTxnId) {
            return fields[0].trim();
        }

        return fileId + ":line:" + lineNo;
    }

    // Returns the first validation error code, or null when the row is valid
    private static String firstProblem(String accountId, Long amountCents, String currency, String txnDate) {
        if (amountCents == null) {
            return "BAD_AMOUNT";
        }

        if (accountId.isEmpty()) {
            return "MISSING_ACCOUNT";
        }

        boolean currencyValid = CURRENCY_CODE.matcher(currency).matches();

        if (!currencyValid) {
            return "BAD_CURRENCY";
        }

        boolean dateValid = isIsoDate(txnDate);

        if (!dateValid) {
            return "BAD_DATE";
        }

        return null;
    }

    private static Long toCents(String amount) {
        try {
            BigDecimal dollars = new BigDecimal(amount);

            return dollars.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static boolean isIsoDate(String text) {
        try {
            LocalDate.parse(text);

            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private CsvRowParser() {}
}
