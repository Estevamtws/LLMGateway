package com.llmgateway.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Prices per model, in USD per million tokens.
 */
@ConfigurationProperties("gateway.pricing")
public record PricingProperties(Map<String, ModelPrice> models) {

    public PricingProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
        models.forEach((model, price) -> {
            if (price == null || price.inputPerMillion() == null || price.outputPerMillion() == null) {
                throw new IllegalArgumentException("Model '" + model + "' needs input-per-million and output-per-million");
            }
            if (price.inputPerMillion().signum() < 0 || price.outputPerMillion().signum() < 0) {
                throw new IllegalArgumentException("Prices for model '" + model + "' must not be negative");
            }
        });
    }

    public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }
}
