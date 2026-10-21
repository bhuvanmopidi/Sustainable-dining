package com.sustainabledining.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/** Carbon score c_i in [0, 1], normalised against the WHOLE catalogue's range (not the current meal). */
@Component
public class CarbonScorer {
    private final DishRepository dishes;

    public CarbonScorer(DishRepository dishes) {
        this.dishes = dishes;
    }

    public record Range(BigDecimal min, BigDecimal max) {
        public BigDecimal score(BigDecimal carbon) {
            BigDecimal span = max.subtract(min);
            if (span.signum() <= 0) {
                return BigDecimal.ZERO.setScale(6);
            }
            BigDecimal s = carbon.subtract(min).divide(span, 6, RoundingMode.HALF_UP);
            if (s.signum() < 0) return BigDecimal.ZERO.setScale(6);
            if (s.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE.setScale(6);
            return s;
        }
    }

    public Range currentRange() {
        List<BigDecimal> values = dishes.findAll().stream().map(Dish::getCarbonKgPerServing).toList();
        if (values.isEmpty()) {
            return new Range(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new Range(values.stream().min(BigDecimal::compareTo).orElseThrow(),
                values.stream().max(BigDecimal::compareTo).orElseThrow());
    }

    /** Median carbon score over the whole catalogue: items below it count as low-carbon (context feature 8). */
    public BigDecimal median() {
        Range range = currentRange();
        List<BigDecimal> scores = dishes.findAll().stream()
                .map(d -> range.score(d.getCarbonKgPerServing())).sorted().toList();
        if (scores.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int n = scores.size();
        return n % 2 == 1 ? scores.get(n / 2)
                : scores.get(n / 2 - 1).add(scores.get(n / 2)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }
}