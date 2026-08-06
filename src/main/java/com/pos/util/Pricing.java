package com.pos.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure pricing math (C6) — the port of {@code frontend/src/domain/pricing.js}. Order
 * creation, order edits and payment all funnel through {@link #computeOrderTotals}, so a
 * client-sent total is never trusted: every amount on the wire is <b>recomputed</b> here
 * from the variant's current price and the store's GST slab (backend-plan.md section 1,
 * deferred obligation 2).
 *
 * <p><b>GST is tax-inclusive</b> (requirements.md section 4): the selling price already
 * contains the tax, so it is <i>extracted</i> rather than added — {@code tax = amount -
 * amount / (1 + rate/100)}. There is no exclusive-mode flag here, unlike the frontend's:
 * nothing in this application ever needs it, so porting the flag would be dead code with
 * no caller.
 *
 * <p>Every method is static and side-effect free, matching the frontend's module of pure
 * functions. {@code BigDecimal} throughout, {@link RoundingMode#HALF_UP} at every
 * rounding step — the mode that matches JS's {@code Math.round} on the positive amounts
 * this domain only ever produces (backend-plan.md section 5).
 */
public final class Pricing {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private Pricing() {
    }

    /** Currency rounding: 2 decimal places, half-up. {@code null} in is treated as zero. */
    public static BigDecimal round2(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The GST contained <b>within</b> a tax-inclusive amount at the given slab. A
     * {@code null} or non-positive rate extracts nothing — matching the frontend's
     * {@code rate <= 0 -> 0}.
     */
    public static BigDecimal extractInclusiveTax(BigDecimal inclusiveAmount, BigDecimal taxRatePercent) {
        BigDecimal rate = taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = inclusiveAmount == null ? BigDecimal.ZERO : inclusiveAmount;
        BigDecimal divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, 10, RoundingMode.HALF_UP));
        // Extra scale on the intermediate division: only the final subtraction is
        // rounded to currency precision, exactly as round2(amount - taxableValue) does
        // in the frontend rather than rounding the taxable value first.
        BigDecimal taxableValue = amount.divide(divisor, 10, RoundingMode.HALF_UP);
        return round2(amount.subtract(taxableValue));
    }

    /** One priced line, before or after the tax split — {@link #computeLineTotals}'s input and output. */
    public static class LineInput {
        public final Long variantId;
        public final String name;
        public final String qrCode;
        public final int quantity;
        public final BigDecimal unitPrice;
        public final BigDecimal taxRatePercent;
        public final BigDecimal lineDiscount;

        public LineInput(Long variantId, String name, String qrCode, int quantity,
                         BigDecimal unitPrice, BigDecimal taxRatePercent, BigDecimal lineDiscount) {
            this.variantId = variantId;
            this.name = name;
            this.qrCode = qrCode;
            this.quantity = Math.max(0, quantity);
            this.unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
            this.taxRatePercent = taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
            this.lineDiscount = round2(lineDiscount);
        }
    }

    /** {@link LineInput} plus the amounts derived from it. Immutable, like its input. */
    public static class LineTotals {
        public final LineInput input;
        public final BigDecimal gross;
        public final BigDecimal lineTotal;
        public final BigDecimal lineTax;
        public final BigDecimal taxableValue;

        LineTotals(LineInput input, BigDecimal gross, BigDecimal lineTotal,
                  BigDecimal lineTax, BigDecimal taxableValue) {
            this.input = input;
            this.gross = gross;
            this.lineTotal = lineTotal;
            this.lineTax = lineTax;
            this.taxableValue = taxableValue;
        }
    }

    /**
     * {@code computeLineTotals} — gross, the discounted line total, and the inclusive tax
     * split out of it.
     */
    public static LineTotals computeLineTotals(LineInput line) {
        BigDecimal gross = round2(line.unitPrice.multiply(BigDecimal.valueOf(line.quantity)));
        BigDecimal lineTotal = round2(gross.subtract(line.lineDiscount));
        BigDecimal lineTax = extractInclusiveTax(lineTotal, line.taxRatePercent);
        BigDecimal taxableValue = round2(lineTotal.subtract(lineTax));
        return new LineTotals(line, gross, lineTotal, lineTax, taxableValue);
    }

    /** One GST slab's share of an order — the payment screen and receipt's breakup table. */
    public static class TaxBreakupEntry {
        public final BigDecimal taxRatePercent;
        public final BigDecimal taxableValue;
        public final BigDecimal taxAmount;
        public final BigDecimal cgst;
        public final BigDecimal sgst;

        TaxBreakupEntry(BigDecimal taxRatePercent, BigDecimal taxableValue, BigDecimal taxAmount) {
            this.taxRatePercent = taxRatePercent;
            this.taxableValue = taxableValue;
            this.taxAmount = taxAmount;
            this.cgst = round2(taxAmount.divide(TWO, 10, RoundingMode.HALF_UP));
            this.sgst = this.cgst;
        }
    }

    /**
     * Groups already-computed lines by slab, ascending. Each per-line tax was rounded
     * before this sums it — matching the frontend, which accumulates rounded amounts
     * rather than rounding the group total once at the end.
     */
    public static List<TaxBreakupEntry> computeTaxBreakup(List<LineTotals> lines) {
        Map<BigDecimal, BigDecimal[]> groups = new LinkedHashMap<>(); // rate -> {taxableValue, taxAmount}
        for (LineTotals line : lines) {
            BigDecimal rate = line.input.taxRatePercent;
            BigDecimal[] group = groups.computeIfAbsent(rate, r -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            group[0] = round2(group[0].add(line.taxableValue));
            group[1] = round2(group[1].add(line.lineTax));
        }
        return groups.entrySet().stream()
                .map(e -> new TaxBreakupEntry(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted((a, b) -> a.taxRatePercent.compareTo(b.taxRatePercent))
                .toList();
    }

    /** The full totals block for an order — and, reusing identical math, a refund (C7). */
    public static class OrderTotals {
        public final List<LineTotals> lines;
        public final BigDecimal subtotal;
        public final BigDecimal totalTax;
        public final List<TaxBreakupEntry> taxBreakup;
        public final BigDecimal orderDiscount;
        public final BigDecimal roundOff;
        public final BigDecimal grandTotal;

        OrderTotals(List<LineTotals> lines, BigDecimal subtotal, BigDecimal totalTax,
                   List<TaxBreakupEntry> taxBreakup, BigDecimal orderDiscount,
                   BigDecimal roundOff, BigDecimal grandTotal) {
            this.lines = lines;
            this.subtotal = subtotal;
            this.totalTax = totalTax;
            this.taxBreakup = taxBreakup;
            this.orderDiscount = orderDiscount;
            this.roundOff = roundOff;
            this.grandTotal = grandTotal;
        }
    }

    /**
     * {@code computeOrderTotals} — reconciles {@code subtotal - orderDiscount + roundOff =
     * grandTotal}. Tax is inclusive, so it is already inside {@code subtotal} and adds
     * nothing on top; {@code grandTotal} rounds to the nearest rupee and {@code roundOff}
     * records the delta.
     */
    public static OrderTotals computeOrderTotals(List<LineInput> items, BigDecimal orderDiscount) {
        List<LineTotals> lines = items.stream().map(Pricing::computeLineTotals).toList();

        BigDecimal subtotal = round2(lines.stream()
                .map(l -> l.lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal totalTax = round2(lines.stream()
                .map(l -> l.lineTax).reduce(BigDecimal.ZERO, BigDecimal::add));
        List<TaxBreakupEntry> taxBreakup = computeTaxBreakup(lines);
        BigDecimal discount = round2(orderDiscount);

        BigDecimal preRoundTotal = round2(subtotal.subtract(discount));
        BigDecimal grandTotal = preRoundTotal.setScale(0, RoundingMode.HALF_UP).setScale(2);
        BigDecimal roundOff = round2(grandTotal.subtract(preRoundTotal));

        return new OrderTotals(lines, subtotal, totalTax, taxBreakup, discount, roundOff, grandTotal);
    }

    /** Cash change due, never negative. */
    public static BigDecimal computeChange(BigDecimal amountTendered, BigDecimal grandTotal) {
        BigDecimal tendered = amountTendered == null ? BigDecimal.ZERO : amountTendered;
        BigDecimal total = grandTotal == null ? BigDecimal.ZERO : grandTotal;
        BigDecimal change = tendered.subtract(total);
        return change.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(2) : round2(change);
    }
}
