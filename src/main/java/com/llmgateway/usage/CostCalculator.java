package com.llmgateway.usage;

import com.llmgateway.provider.LlmProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.TreeSet;

/**
 * Computes the cost of a call at request time (D4). Fails at startup if any served model has no price.
 */
@Component
public class CostCalculator {

    static final int SCALE = 8;
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final PricingProperties pricing;

    public CostCalculator(PricingProperties pricing, List<LlmProvider> providers) {
        this.pricing = pricing;
        TreeSet<String> missing = new TreeSet<>();
        providers.forEach(provider -> provider.models().stream()
                .filter(model -> !pricing.models().containsKey(model))
                .forEach(missing::add));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No price configured for models " + missing
                    + " (set gateway.pricing.models.<model>.input-per-million and output-per-million)");
        }
    }

    /**
     * {@code (input * inputPrice + output * outputPrice) / 1,000,000}, rounded half-up to 8 decimal places.
     */
    public BigDecimal cost(String model, int inputTokens, int outputTokens) {
        PricingProperties.ModelPrice price = pricing.models().get(model);
        if (price == null) {
            throw new IllegalArgumentException("No price configured for model '" + model + "'");
        }
        BigDecimal total = price.inputPerMillion().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(outputTokens)));
        return total.divide(ONE_MILLION, SCALE, RoundingMode.HALF_UP);
    }
}
