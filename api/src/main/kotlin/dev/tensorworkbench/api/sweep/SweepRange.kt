package dev.tensorworkbench.api.sweep

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Expands start..end by step into parameter values using exact decimal arithmetic.
 *
 * Values are start + k * step for k = 0, 1, 2, ... while the value is <= end.
 * The end is included only when it lands exactly on a step, so 1..20 step 1 gives
 * 20 values and 0..1 step 0.3 gives 0, 0.3, 0.6, 0.9. Because values are computed as
 * start + k * step (not by repeated addition of a binary float), 0.1 steps never drift.
 */
object SweepRange {

    fun count(start: BigDecimal, end: BigDecimal, step: BigDecimal): Long {
        require(step.signum() > 0) { "step must be positive" }
        require(start <= end) { "start must not exceed end" }
        return end.subtract(start).divide(step, 0, RoundingMode.FLOOR).longValueExact() + 1
    }

    fun expand(start: BigDecimal, end: BigDecimal, step: BigDecimal): List<BigDecimal> {
        val n = count(start, end, step)
        return (0 until n).map { k -> start.add(step.multiply(BigDecimal.valueOf(k))).stripTrailingZeros() }
    }
}
