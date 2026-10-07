package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CompetitorSkuParserTest {
    private final CompetitorSkuParser parser = new CompetitorSkuParser(new ObjectMapper());
    private CompetitorSkuSnapshot parse(String item) {
        return parser.parse("{\"data\":{\"itemDO\":" + item + "}}", "1085721375729", "12.70", Instant.EPOCH);
    }

    @Test void readsBothListsAndDoublePropertiesWithoutLosingStringIds() {
        var snapshot = parse("""
            {"skuList":[],"idleItemSkuList":[{"skuId":"999999999999999999999",
             "priceInCent":"1270","quantity":"0","propertyList":[
              {"propertyText":"地区","valueText":"日本","propertyId":"001","valueId":"002","propertySortOrder":2,"valueSortOrder":1},
              {"propertyText":"面值","valueText":"1000","propertyId":"003","valueId":"004"}]}]}
            """);
        assertEquals("AVAILABLE", snapshot.status());
        var sku = snapshot.skus().getFirst();
        assertEquals("999999999999999999999", sku.skuId());
        assertEquals(1270L, sku.priceCents());
        assertEquals(0L, sku.quantity());
        assertEquals(2, sku.properties().size());
        assertEquals("001", sku.properties().getFirst().propertyId());
        assertEquals(2, sku.properties().getFirst().propertySortOrder());
    }

    @Test void prefersNonEmptyPrimaryAndSeparatesAbsentFromEmpty() {
        assertEquals("NO_SKU", parse("{\"skuList\":[]}").status());
        assertEquals("NO_SKU", parse("{\"idleItemSkuList\":[]}").status());
        assertEquals("MISSING", parse("{}").status());
        assertEquals("MISSING", parse("{\"skuList\":null}").status());
        var value = parse("""
            {"skuList":[{"skuId":123,"priceInCent":50,"properties":[{"name":"颜色","value":"蓝"}]}],"idleItemSkuList":[]}
            """);
        assertEquals("AVAILABLE", value.status());
        assertEquals("123", value.skus().getFirst().skuId());
        assertNull(value.skus().getFirst().quantity());
    }

    @Test void neverGuessesUnitsAndRetainsPartialFacts() {
        var result = parse("""
            {"skuList":[
              {"skuId":"a","price":"12.70","quantity":-1},
              {"skuId":"b","priceInCent":"1.5","quantity":"bad"},
              {"skuId":"c","priceInCent":-2},
              {"skuId":"d","priceInCent":"9007199254740992"},
              {"skuId":"e"},null,{"priceInCent":1200},
              {"skuId":"f","priceInCent":1200,"propertyValues":[{"name":"颜色","value":"蓝"},null]},
              {"skuId":"f","priceInCent":1}]}
            """);
        assertEquals("PARTIAL", result.status());
        assertEquals(6, result.skus().size());
        assertEquals("UNIT_UNCONFIRMED", result.skus().getFirst().priceStatus());
        assertEquals("12.70", result.skus().getFirst().rawPrice());
        assertNull(result.skus().getFirst().priceCents());
        assertNull(result.skus().getFirst().quantity());
        assertEquals("INVALID", result.skus().get(1).priceStatus());
        assertEquals("MISSING", result.skus().get(4).priceStatus());
        assertEquals(1200L, result.skus().getLast().priceCents());
        assertEquals(1, result.skus().getLast().properties().size());
    }
}
