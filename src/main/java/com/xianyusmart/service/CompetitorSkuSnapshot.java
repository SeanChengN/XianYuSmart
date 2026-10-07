package com.xianyusmart.service;

import java.util.List;

/** Read-only competitor facts, never delivery inventory or publishing configuration. */
public record CompetitorSkuSnapshot(
        String itemId, String displayPrice, String status, String source,
        String capturedAt, String attemptedAt, boolean stale, String message, String reason,
        List<Sku> skus) {

    public record Sku(String skuId, Long priceCents, String rawPrice, String priceStatus,
                      Long quantity, List<Property> properties) { }

    public record Property(String name, String value, String propertyId, String valueId,
                           Integer propertySortOrder, Integer valueSortOrder) { }
}
