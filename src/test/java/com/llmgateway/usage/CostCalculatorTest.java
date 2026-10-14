package com.llmgateway.usage;

import com.llmgateway.provider.ChatRequest;
import com.llmgateway.provider.ChatResult;
import com.llmgateway.provider.LlmProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostCalculatorTest {

    private static final PricingProperties PRICING = new PricingProperties(Map.of(
            "cheap", price("0.15", "0.60"),
            "pricey", price("15", "75"),
            "free", price("0", "0")));

    private final CostCalculator calculator = new CostCalculator(PRICING, List.of(provider("cheap", "pricey", "free")));

    @Test
    void appliesFormulaWithSeparateInputAndOutputPrices() {
        // (1000 * 15 + 500 * 75) / 1,000,000 = 0.0525
        assertThat(calculator.cost("pricey", 1000, 500)).isEqualTo(new BigDecimal("0.05250000"));
    }

    @Test
    void alwaysHasEightDecimalPlaces() {
        assertThat(calculator.cost("free", 1000, 1000)).isEqualTo(new BigDecimal("0E-8")).hasScaleOf(8);
        assertThat(calculator.cost("cheap", 0, 0)).hasScaleOf(8);
    }

    @Test
    void roundsHalfUpToEightDecimalPlaces() {
        // 1 * 0.15 / 1,000,000 = 0.00000015 exactly
        assertThat(calculator.cost("cheap", 1, 0)).isEqualTo(new BigDecimal("0.00000015"));
        // 1 * 0.60 / 1,000,000 = 0.0000006
        assertThat(calculator.cost("cheap", 0, 1)).isEqualTo(new BigDecimal("0.00000060"));
        // 0.000000005 rounds up to 0.00000001
        CostCalculator tiny = new CostCalculator(new PricingProperties(Map.of("t", price("0.005", "0"))), List.of());
        assertThat(tiny.cost("t", 1, 0)).isEqualTo(new BigDecimal("0.00000001"));
        // 0.0000000049 rounds down to 0
        CostCalculator tinier = new CostCalculator(new PricingProperties(Map.of("t", price("0.0049", "0"))), List.of());
        assertThat(tinier.cost("t", 1, 0)).isEqualTo(new BigDecimal("0E-8"));
    }

    @Test
    void handlesLargeTokenCountsWithoutLosingPrecision() {
        assertThat(calculator.cost("pricey", Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isEqualTo(new BigDecimal("193273.52823000"));
    }

    @Test
    void failsAtStartupWhenAServedModelHasNoPrice() {
        assertThatThrownBy(() -> new CostCalculator(PRICING, List.of(provider("cheap", "unpriced-b", "unpriced-a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[unpriced-a, unpriced-b]");
    }

    @Test
    void rejectsIncompleteOrNegativePrices() {
        assertThatThrownBy(() -> new PricingProperties(Map.of("m", new PricingProperties.ModelPrice(null, BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingProperties(Map.of("m", price("-1", "1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PricingProperties.ModelPrice price(String input, String output) {
        return new PricingProperties.ModelPrice(new BigDecimal(input), new BigDecimal(output));
    }

    private static LlmProvider provider(String... models) {
        return new LlmProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public Set<String> models() {
                return Set.of(models);
            }

            @Override
            public ChatResult complete(ChatRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
