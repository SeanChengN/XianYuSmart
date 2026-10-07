package com.xianyusmart.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompetitorSnapshots {
    private static final Pattern ITEM_ID = Pattern.compile("(?:[?&]id=|/item/)(\\d{8,64})");
    private static final java.util.List<String> SERVER_FIELDS = java.util.List.of(
            CompetitorDetailService.SNAPSHOT_KEY, "displayPrice", "priceSource", "detailStatus", "detailMessage");

    private CompetitorSnapshots() { }

    static Map<String, Object> edit(Map<String, Object> old, Map<String, Object> input,
                                    Long oldAccount, Long newAccount, String oldItem, String newItem) {
        Map<String, Object> result = new LinkedHashMap<>(old);
        if (input != null) input.forEach((key, value) -> {
            if (!SERVER_FIELDS.contains(key)) result.put(key, value);
        });
        String oldSource = itemId(old.get("sourceUrl"), oldItem);
        String newSource = itemId(result.get("sourceUrl"), newItem);
        if (!Objects.equals(oldAccount, newAccount) || !Objects.equals(oldItem, newItem)
                || !Objects.equals(oldSource, newSource)) {
            SERVER_FIELDS.forEach(result::remove);
            result.remove("price");
            result.remove("itemId");
        }
        return result;
    }

    static Map<String, Object> untrustedCandidate(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>(input);
        SERVER_FIELDS.forEach(result::remove);
        result.put("displayPrice", result.getOrDefault("price", ""));
        result.put("priceSource", "SEARCH_DISPLAY");
        result.put("sourceUrl", "https://www.goofish.com/item?id=" + result.getOrDefault("itemId", ""));
        return result;
    }

    static String itemId(Object sourceUrl, String fallback) {
        Matcher matcher = ITEM_ID.matcher(String.valueOf(sourceUrl));
        return matcher.find() ? matcher.group(1) : fallback;
    }
}
