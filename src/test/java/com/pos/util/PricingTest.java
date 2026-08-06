package com.pos.util;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The port of {@code frontend/src/domain/pricing.test.js} (C6) — no database, no Spring
 * context, a pure-math test for a pure-math class. Same inputs as the JS suite, and every
 * assertion below is the identical rupee figure.
 *
 * <p><b>Not a line-for-line port.</b> Two shapes in the JS suite have no Java equivalent by
 * design, not by oversight:
 *
 * <ul>
 *   <li>The exclusive-tax-mode cases — {@link Pricing} never ports the {@code taxInclusive}
 *       flag at all (see its class Javadoc): nothing in this application calls it, so a
 *       Java case exercising it would be testing code that does not exist.</li>
 *   <li>"does not mutate the input" and "coerces non-numbers to 0" — {@link Pricing.LineInput}
 *       is immutable by construction (every field {@code final}, set once in the
 *       constructor) and every argument is statically typed {@code BigDecimal}/{@code int},
 *       so neither failure mode is reachable in Java. Nothing to assert.</li>
 * </ul>
 *
 * <p>{@code computeRefundTotals} has no Java equivalent either (C7): the frontend's
 * version is a thin wrapper — {@code computeOrderTotals} with the refund lines and no
 * order-level discount, then a field-name remap ({@code subtotal} → {@code
 * refundSubtotal}, {@code lineTotal} → {@code lineRefund}, and so on). {@code
 * ReturnService.create} does exactly that remap itself rather than through a second
 * {@link Pricing} method, so the frontend's {@code computeRefundTotals} case below is
 * ported as a {@code computeOrderTotals} call and asserted against the same numbers —
 * see {@code ReturnWriteIT} for the same property proven through the real endpoint.
 */
@DisplayName("Pricing")
class PricingTest {

    /** Rupees to 2dp, matching every {@code BigDecimal} this class hands back. */
    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static Pricing.LineInput line(BigDecimal unitPrice, int quantity, BigDecimal taxRatePercent) {
        return new Pricing.LineInput(1L, "line", null, quantity, unitPrice, taxRatePercent, BigDecimal.ZERO);
    }

    private static Pricing.LineInput line(BigDecimal unitPrice, int quantity, BigDecimal taxRatePercent,
                                          BigDecimal lineDiscount) {
        return new Pricing.LineInput(1L, "line", null, quantity, unitPrice, taxRatePercent, lineDiscount);
    }

    @Nested
    @DisplayName("round2")
    class Round2 {

        @Test
        @DisplayName("rounds to 2 decimals, half-up")
        void roundsToTwoDecimals() {
            assertEquals(money("1.01"), Pricing.round2(money("1.005")));
            assertEquals(money("2.34"), Pricing.round2(money("2.344")));
            assertEquals(money("2.35"), Pricing.round2(money("2.345")));
        }

        @Test
        @DisplayName("treats a null amount as zero")
        void treatsNullAsZero() {
            assertEquals(money("0.00"), Pricing.round2(null));
        }
    }

    @Nested
    @DisplayName("extractInclusiveTax")
    class ExtractInclusiveTax {

        @Test
        @DisplayName("extracts the GST contained in an inclusive price")
        void extractsGstFromAnInclusivePrice() {
            // 118 inclusive @ 18% -> taxable 100, tax 18
            assertEquals(money("18.00"), Pricing.extractInclusiveTax(money("118"), money("18")));
            // 105 inclusive @ 5% -> taxable 100, tax 5
            assertEquals(money("5.00"), Pricing.extractInclusiveTax(money("105"), money("5")));
        }

        @Test
        @DisplayName("is zero for a 0% slab")
        void isZeroForAZeroSlab() {
            assertEquals(BigDecimal.ZERO, Pricing.extractInclusiveTax(money("100"), money("0")));
        }
    }

    @Nested
    @DisplayName("computeLineTotals")
    class ComputeLineTotals {

        @Test
        @DisplayName("computes gross, lineTotal, and the inclusive tax split")
        void computesGrossLineTotalAndTaxSplit() {
            Pricing.LineTotals result = Pricing.computeLineTotals(line(money("59"), 2, money("18")));

            assertEquals(money("118.00"), result.gross);
            assertEquals(money("118.00"), result.lineTotal);
            assertEquals(money("18.00"), result.lineTax);
            assertEquals(money("100.00"), result.taxableValue);
        }

        @Test
        @DisplayName("applies a line discount before extracting tax")
        void appliesALineDiscountBeforeExtractingTax() {
            Pricing.LineTotals result =
                    Pricing.computeLineTotals(line(money("59"), 2, money("18"), money("18")));

            assertEquals(money("100.00"), result.lineTotal);
            assertEquals(money("100.00"), Pricing.round2(result.taxableValue.add(result.lineTax)));
        }
    }

    @Nested
    @DisplayName("computeTaxBreakup")
    class ComputeTaxBreakup {

        @Test
        @DisplayName("groups lines by slab, ascending, and splits cgst/sgst evenly")
        void groupsLinesBySlabAndSplitsCgstSgst() {
            List<Pricing.LineTotals> lines = List.of(
                    Pricing.computeLineTotals(line(money("118"), 1, money("18"))),
                    Pricing.computeLineTotals(line(money("105"), 1, money("5"))),
                    Pricing.computeLineTotals(line(money("100"), 1, money("5"))));

            List<Pricing.TaxBreakupEntry> breakup = Pricing.computeTaxBreakup(lines);

            assertEquals(List.of(money("5"), money("18")),
                    breakup.stream().map(g -> g.taxRatePercent).toList());

            Pricing.TaxBreakupEntry slab5 = breakup.stream()
                    .filter(g -> g.taxRatePercent.compareTo(money("5")) == 0)
                    .findFirst().orElseThrow();
            BigDecimal expectedTax = Pricing.round2(
                    money("5").add(Pricing.extractInclusiveTax(money("100"), money("5"))));
            assertEquals(expectedTax, slab5.taxAmount);
            assertEquals(Pricing.round2(expectedTax.divide(BigDecimal.valueOf(2))), slab5.cgst);
            assertEquals(Pricing.round2(expectedTax.divide(BigDecimal.valueOf(2))), slab5.sgst);
        }
    }

    @Nested
    @DisplayName("computeOrderTotals")
    class ComputeOrderTotals {

        @Test
        @DisplayName("reconciles subtotal, roundOff and grandTotal (inclusive)")
        void reconcilesSubtotalRoundOffAndGrandTotal() {
            List<Pricing.LineInput> items = List.of(
                    line(money("59"), 2, money("18")),   // 118
                    line(money("30"), 1, money("5")));    // 30

            Pricing.OrderTotals totals = Pricing.computeOrderTotals(items, BigDecimal.ZERO);

            assertEquals(money("148.00"), totals.subtotal);
            // Already whole, so nothing to round away.
            assertEquals(money("148.00"), totals.grandTotal);
            assertEquals(money("0.00"), totals.roundOff);
            assertEquals(totals.grandTotal,
                    Pricing.round2(totals.subtotal.subtract(totals.orderDiscount).add(totals.roundOff)));
        }

        @Test
        @DisplayName("records a non-zero roundOff when the total is fractional")
        void recordsANonZeroRoundOffWhenFractional() {
            Pricing.OrderTotals totals = Pricing.computeOrderTotals(
                    List.of(line(money("33.4"), 1, money("5"))), BigDecimal.ZERO);

            assertEquals(money("33.00"), totals.grandTotal);
            assertEquals(Pricing.round2(money("33").subtract(money("33.4"))), totals.roundOff);
            assertEquals(totals.grandTotal, Pricing.round2(totals.subtotal.add(totals.roundOff)));
        }

        @Test
        @DisplayName("applies an order-level discount before rounding")
        void appliesAnOrderLevelDiscountBeforeRounding() {
            Pricing.OrderTotals totals = Pricing.computeOrderTotals(
                    List.of(line(money("100"), 1, money("18"))), money("10"));

            assertEquals(money("90.00"), totals.grandTotal);
            assertEquals(money("10.00"), totals.orderDiscount);
        }

        @Test
        @DisplayName("handles an empty cart")
        void handlesAnEmptyCart() {
            Pricing.OrderTotals totals = Pricing.computeOrderTotals(List.of(), BigDecimal.ZERO);

            assertEquals(money("0.00"), totals.subtotal);
            assertEquals(money("0.00"), totals.grandTotal);
            assertTrue(totals.taxBreakup.isEmpty());
        }
    }

    @Nested
    @DisplayName("computeOrderTotals used as a refund (C7)")
    class ComputeRefundTotals {

        @Test
        @DisplayName("reuses order math and the field-remapped result matches pricing.test.js's case")
        void reusesOrderMathForARefund() {
            // pricing.test.js: computeRefundTotals([{ unitPrice: 59, quantity: 1,
            // taxRatePercent: 18 }]) -> refundSubtotal 59, refundTax extractInclusiveTax(59,18),
            // refundTotal 59, lines[0].lineRefund 59. No order-level discount on a refund.
            Pricing.OrderTotals totals = Pricing.computeOrderTotals(
                    List.of(line(money("59"), 1, money("18"))), BigDecimal.ZERO);

            assertEquals(money("59.00"), totals.subtotal); // -> refundSubtotal
            assertEquals(Pricing.extractInclusiveTax(money("59"), money("18")), totals.totalTax); // -> refundTax
            assertEquals(money("59.00"), totals.grandTotal); // -> refundTotal
            assertEquals(money("59.00"), totals.lines.get(0).lineTotal); // -> lineRefund
        }
    }

    @Nested
    @DisplayName("computeChange")
    class ComputeChange {

        @Test
        @DisplayName("returns tendered minus total, never negative")
        void returnsTenderedMinusTotalNeverNegative() {
            assertEquals(money("52.00"), Pricing.computeChange(money("200"), money("148")));
            assertEquals(money("0.00"), Pricing.computeChange(money("100"), money("148")));
        }
    }
}
