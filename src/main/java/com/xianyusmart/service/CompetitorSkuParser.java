package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CompetitorSkuParser {
    private final ObjectMapper mapper;

    CompetitorSkuParser(ObjectMapper mapper) { this.mapper = mapper; }

    CompetitorSkuSnapshot parse(String response, String itemId, String displayPrice, Instant now) {
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode data = root.has("data") ? root.path("data") : root;
            JsonNode item = data.has("itemDO") ? data.path("itemDO")
                    : data.has("item") ? data.path("item") : data;
            JsonNode list = item.get("skuList");
            JsonNode alternate = item.get("idleItemSkuList");
            if (list == null || !list.isArray() || list.isEmpty()) {
                if (alternate != null && alternate.isArray()) list = alternate;
            }
            List<CompetitorSkuSnapshot.Sku> skus = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean partial = false;
            if (list != null && list.isArray()) {
                for (JsonNode sku : list) {
                    String skuId = text(sku.get("skuId"));
                    if (!sku.isObject() || skuId.isBlank() || !seen.add(skuId)) {
                        partial = true;
                        continue;
                    }
                    Long priceCents = nonNegativeLong(sku.get("priceInCent"));
                    String rawPrice = text(sku.get("price"));
                    String priceStatus = priceCents != null ? "KNOWN"
                            : sku.hasNonNull("priceInCent") ? "INVALID"
                            : !rawPrice.isBlank() ? "UNIT_UNCONFIRMED" : "MISSING";
                    if (!"KNOWN".equals(priceStatus)) partial = true;
                    List<CompetitorSkuSnapshot.Property> properties = new ArrayList<>();
                    JsonNode props = sku.get("propertyList");
                    if (props == null || !props.isArray() || props.isEmpty()) props = sku.get("properties");
                    if (props == null || !props.isArray() || props.isEmpty()) props = sku.get("propertyValues");
                    if (props != null && props.isArray()) {
                        for (JsonNode prop : props) {
                            String name = firstText(prop, "propertyText", "name", "propertyName");
                            String value = firstText(prop, "valueText", "value", "propertyValue", "actualValueText");
                            if (!prop.isObject() || name.isBlank() || value.isBlank()) {
                                partial = true;
                                continue;
                            }
                            properties.add(new CompetitorSkuSnapshot.Property(name, value,
                                    text(prop.get("propertyId")), text(prop.get("valueId")),
                                    integer(prop.get("propertySortOrder")), integer(prop.get("valueSortOrder"))));
                        }
                    }
                    if (properties.isEmpty()) partial = true;
                    skus.add(new CompetitorSkuSnapshot.Sku(skuId, priceCents, rawPrice, priceStatus,
                            nonNegativeLong(sku.get("quantity")), List.copyOf(properties)));
                }
            }
            String status = list == null || !list.isArray() ? "MISSING"
                    : partial ? "PARTIAL" : skus.isEmpty() ? "NO_SKU" : "AVAILABLE";
            String message = switch (status) {
                case "MISSING" -> "平台详情未提供 SKU 字段，无法确认规格价格";
                case "NO_SKU" -> "平台返回空 SKU 列表；展示价不代表逐规格售价";
                case "PARTIAL" -> "部分规格资料不完整；仅标明单位的价格可用于参考";
                default -> "已取得平台规格价格";
            };
            return new CompetitorSkuSnapshot(itemId, displayPrice, status, "DETAIL_API",
                    now.toString(), now.toString(), false, message, null, List.copyOf(skus));
        } catch (Exception e) {
            throw new IllegalStateException("竞品规格响应无法解析", e);
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isValueNode() && !node.isNull() ? node.asText().trim() : "";
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static Long nonNegativeLong(JsonNode node) {
        try {
            long value = new BigDecimal(text(node)).longValueExact();
            // Keep the JSON integer exactly representable in browser clients.
            return value >= 0 && value <= 9_007_199_254_740_991L ? value : null;
        } catch (NumberFormatException | ArithmeticException e) { return null; }
    }

    private static Integer integer(JsonNode node) {
        try { return new BigDecimal(text(node)).intValueExact(); }
        catch (NumberFormatException | ArithmeticException e) { return null; }
    }
}
