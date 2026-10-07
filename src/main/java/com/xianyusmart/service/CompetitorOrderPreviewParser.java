package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Extracts public SKU facts only; never retains transaction or address components. */
final class CompetitorOrderPreviewParser {
    private final ObjectMapper mapper;

    CompetitorOrderPreviewParser(ObjectMapper mapper) { this.mapper = mapper; }

    Map<String, Object> parse(String response, String itemId, Instant now) {
        try {
            JsonNode data = mapper.readTree(response).path("data");
            JsonNode components = data.path("components");
            if (!components.isArray()) components = data.path("renderVO").path("components");
            JsonNode item = component(components, "itemDescVO");
            JsonNode selector = component(components, "skuSelectorVO");
            String displayPrice = text(item.path("price"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("itemId", itemId);
            if (!text(item.path("title")).isBlank()) result.put("title", text(item.path("title")));
            if (!displayPrice.isBlank()) result.put("price", displayPrice);
            result.put("displayPrice", displayPrice);
            result.put("priceSource", "ORDER_PREVIEW_DISPLAY");
            result.put("detailStatus", "ORDER_PREVIEW");
            result.put("sourceUrl", "https://www.goofish.com/item?id=" + itemId);
            String picture = text(item.path("picUrl"));
            if (picture.startsWith("https://") || picture.startsWith("http://")) {
                result.put("images", List.of(picture.replaceFirst("^http://", "https://")));
            }
            result.put(CompetitorDetailService.SNAPSHOT_KEY, parseSkus(selector, itemId, displayPrice, now));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("竞品确认页规格响应无法解析", e);
        }
    }

    private CompetitorSkuSnapshot parseSkus(JsonNode selector, String itemId, String displayPrice, Instant now) {
        JsonNode details = selector.path("skuInfoDetails");
        List<Group> groups = new ArrayList<>();
        boolean partial = false;
        JsonNode specifications = selector.path("specificationInfoList");
        if (specifications.isArray()) {
            Set<String> groupIds = new HashSet<>();
            for (JsonNode group : specifications) {
                String id = text(group.path("specificationId"));
                String name = text(group.path("specificationName"));
                if (id.isBlank() || name.isBlank() || !groupIds.add(id)) { partial = true; continue; }
                Map<String, CompetitorSkuSnapshot.Property> options = new HashMap<>();
                if (!group.path("specificationList").isArray()) partial = true;
                for (JsonNode option : group.path("specificationList")) {
                    String optionId = text(option.path("id"));
                    String path = optionId.contains(":") ? optionId : id + ":" + optionId;
                    String value = text(option.path("value"));
                    if (optionId.isBlank() || !path.startsWith(id + ":") || value.isBlank()
                            || options.containsKey(path)) { partial = true; continue; }
                    options.put(path, new CompetitorSkuSnapshot.Property(name, value, id,
                            path.substring(id.length() + 1), integer(group.path("sortId")),
                            integer(option.path("sortId"))));
                }
                groups.add(new Group(id, integer(group.path("sortId")), options));
            }
        }
        groups.sort(Comparator.comparing(Group::order, Comparator.nullsLast(Integer::compareTo)));
        List<CompetitorSkuSnapshot.Sku> skus = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (details.isObject()) {
            for (var entries = details.fields(); entries.hasNext();) {
                var entry = entries.next();
                JsonNode sku = entry.getValue();
                String skuId = text(sku.path("skuId"));
                if (!sku.isObject() || skuId.isBlank() || !seen.add(skuId)) { partial = true; continue; }
                String[] rawPaths = entry.getKey().split("#", -1);
                Set<String> paths = new HashSet<>(Arrays.asList(rawPaths));
                if (paths.size() != rawPaths.length) partial = true;
                List<CompetitorSkuSnapshot.Property> properties = new ArrayList<>();
                for (Group group : groups) {
                    List<CompetitorSkuSnapshot.Property> matches = paths.stream()
                            .map(group.options()::get).filter(Objects::nonNull).toList();
                    if (matches.size() != 1) { partial = true; continue; }
                    properties.add(matches.getFirst());
                }
                if (properties.isEmpty() || paths.size() != groups.size()
                        || properties.size() != groups.size()) partial = true;
                String rawPrice = text(sku.path("skuPrice"));
                // Verified order.render v7.0 skuPrice is yuan, unlike unverified detail.price.
                Long priceCents = yuanCents(rawPrice);
                String priceStatus = priceCents != null ? "KNOWN" : rawPrice.isBlank() ? "MISSING" : "INVALID";
                if (priceCents == null) partial = true;
                Long quantity = nonNegativeLong(sku.path("quantity"));
                if (quantity == null) partial = true;
                skus.add(new CompetitorSkuSnapshot.Sku(skuId, priceCents, rawPrice, priceStatus,
                        quantity, List.copyOf(properties)));
            }
        }
        skus.sort((left, right) -> {
            for (int i = 0; i < Math.min(left.properties().size(), right.properties().size()); i++) {
                int comparison = Comparator.nullsLast(Integer::compareTo).compare(
                        left.properties().get(i).valueSortOrder(), right.properties().get(i).valueSortOrder());
                if (comparison != 0) return comparison;
            }
            return left.skuId().compareTo(right.skuId());
        });
        String status = !details.isObject() ? "MISSING" : partial ? "PARTIAL"
                : skus.isEmpty() ? "NO_SKU" : "AVAILABLE";
        String message = switch (status) {
            case "MISSING" -> "平台确认页未提供 SKU 字段，无法确认规格价格";
            case "NO_SKU" -> "平台确认页返回空 SKU 列表；展示价不代表逐规格售价";
            case "PARTIAL" -> "部分确认页规格资料不完整；缺失价格或库存不作推测";
            default -> "已取得闲鱼确认页规格价格；商品描述和卖家资料不由该接口提供";
        };
        return new CompetitorSkuSnapshot(itemId, displayPrice, status, "ORDER_RENDER_API",
                now.toString(), now.toString(), false, message, null, List.copyOf(skus));
    }

    private static JsonNode component(JsonNode components, String render) {
        for (JsonNode component : components) {
            if (render.equals(text(component.path("render")))) return component.path("data");
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() ? node.asText().trim() : "";
    }

    private static Long yuanCents(String price) {
        if (!price.matches("\\d+(?:\\.\\d{1,2})?")) return null;
        try { return bounded(new BigDecimal(price).movePointRight(2).longValueExact()); }
        catch (NumberFormatException | ArithmeticException e) { return null; }
    }

    private static Long nonNegativeLong(JsonNode node) {
        try { return bounded(new BigDecimal(text(node)).longValueExact()); }
        catch (NumberFormatException | ArithmeticException e) { return null; }
    }

    private static Long bounded(long value) { return value >= 0 && value <= 9_007_199_254_740_991L ? value : null; }
    private static Integer integer(JsonNode node) {
        try { return new BigDecimal(text(node)).intValueExact(); }
        catch (NumberFormatException | ArithmeticException e) { return null; }
    }
    private record Group(String id, Integer order, Map<String, CompetitorSkuSnapshot.Property> options) { }
}
