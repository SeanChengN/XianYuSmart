package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CompetitorOrderPreviewParserTest {
    final ObjectMapper json = new ObjectMapper();
    final CompetitorOrderPreviewParser parser = new CompetitorOrderPreviewParser(json);
    final Instant now = Instant.parse("2026-10-07T07:40:00Z");

    CompetitorSkuSnapshot snapshot(ObjectNode response) throws Exception {
        return (CompetitorSkuSnapshot) parser.parse(json.writeValueAsString(response), "1078417108918", now)
                .get(CompetitorDetailService.SNAPSHOT_KEY);
    }
    ObjectNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/competitor/order-render-public-sku.json")) {
            assertNotNull(input);
            return (ObjectNode) json.readTree(input);
        }
    }
    ObjectNode selector(ObjectNode response) {
        return (ObjectNode) response.path("data").path("components").get(1).path("data");
    }

    @Test void actualTwelveSkuResponseMatchesBrowserAndCentSubtotal() throws Exception {
        var response = fixture();
        var result = snapshot(response);
        assertEquals("AVAILABLE", result.status());
        assertEquals("ORDER_RENDER_API", result.source());
        assertEquals(now.toString(), result.capturedAt());
        assertEquals(12, result.skus().size());
        var one = result.skus().get(0);
        assertEquals("6128291728427", one.skuId());
        assertEquals(730L, one.priceCents());
        assertEquals(0L, one.quantity());
        var two = result.skus().get(1);
        assertEquals(1500L, two.priceCents());
        assertEquals("面值", two.properties().getFirst().name());
        assertEquals("2美", two.properties().getFirst().value());
        assertEquals("-1", two.properties().getFirst().propertyId());
        assertEquals("-2", two.properties().getFirst().valueId());
        assertEquals(2500L, result.skus().get(2).priceCents());
        assertEquals(139800L, result.skus().getLast().priceCents());
    }

    @Test void doubleSpecificationUsesFullCombinationPriceAndGroupOrdering() throws Exception {
        var response = (ObjectNode) json.readTree("""
            {"data":{"components":[{"render":"skuSelectorVO","data":{
              "specificationInfoList":[
                {"specificationId":"color","specificationName":"颜色","sortId":"2","specificationList":[
                  {"id":"color:red","value":"红","sortId":"1","priceText":"¥999"}]},
                {"specificationId":"size","specificationName":"尺寸","sortId":"1","specificationList":[
                  {"id":"L","value":"大","sortId":"2","priceText":"¥888"},
                  {"id":"S","value":"小","sortId":"1","priceText":"¥777"}]}],
              "skuInfoDetails":{
                "color:red#size:L":{"skuId":"string-L","skuPrice":"25.00","quantity":"2"},
                "size:S#color:red":{"skuId":"string-S","skuPrice":"15.00","quantity":"3"}}}}]}}
            """);
        var result = snapshot(response);
        assertEquals("AVAILABLE", result.status());
        assertEquals("string-S", result.skus().getFirst().skuId());
        assertEquals(1500L, result.skus().getFirst().priceCents());
        assertEquals(2500L, result.skus().getLast().priceCents());
        assertEquals("尺寸", result.skus().getFirst().properties().getFirst().name());
        assertEquals("颜色", result.skus().getFirst().properties().getLast().name());
    }

    @Test void missingEmptyAndNestedComponentsRemainDistinct() throws Exception {
        var response = fixture();
        selector(response).remove("skuInfoDetails");
        assertEquals("MISSING", snapshot(response).status());
        selector(response).putObject("skuInfoDetails");
        assertEquals("NO_SKU", snapshot(response).status());
        var nested = json.createObjectNode();
        nested.putObject("data").putObject("renderVO").set("components", fixture().path("data").path("components"));
        assertEquals(12, snapshot(nested).skus().size());
    }

    @Test void invalidPriceNeverGuessesAndMissingInventoryIsNull() throws Exception {
        for (String price : new String[]{"-1", "15.001", "NaN", "¥15", "90071992547409.92", "", "1e2"}) {
            var response = fixture();
            var row = (ObjectNode) selector(response).path("skuInfoDetails").path("-1:-2");
            row.put("skuPrice", price);
            row.put("priceInCent", "999"); // An unrelated field cannot override this verified source.
            row.remove("quantity");
            var result = snapshot(response);
            assertEquals("PARTIAL", result.status());
            assertEquals(12, result.skus().size());
            assertNull(result.skus().get(1).priceCents());
            assertNull(result.skus().get(1).quantity());
        }
        for (String quantity : new String[]{"-1", "0.5", "9007199254740992", "invalid"}) {
            var response = fixture();
            ((ObjectNode) selector(response).path("skuInfoDetails").path("-1:-2")).put("quantity", quantity);
            assertNull(snapshot(response).skus().get(1).quantity());
        }
    }

    @Test void malformedRowsAndUnknownPropertiesPreserveOtherSkus() throws Exception {
        var response = fixture();
        var details = (ObjectNode) selector(response).path("skuInfoDetails");
        details.put("broken", "not-an-object");
        details.set("unknown:path", details.remove("-1:-2"));
        var result = snapshot(response);
        assertEquals("PARTIAL", result.status());
        assertEquals(12, result.skus().size());
        assertTrue(result.skus().stream().anyMatch(sku -> sku.properties().isEmpty()));
    }

    @Test void outputProjectsPublicFactsOnlyAndDoesNotEraseOtherFacts() throws Exception {
        var response = fixture();
        response.putObject("data").put("privateAddress", "synthetic-private");
        var components = fixture().path("data").path("components").deepCopy();
        ((ObjectNode) response.path("data")).set("components", components);
        ((ObjectNode) components.get(0).path("data")).put("privateAddress", "synthetic-private");
        var result = parser.parse(json.writeValueAsString(response), "1078417108918", now);
        assertEquals("ORDER_PREVIEW", result.get("detailStatus"));
        assertFalse(result.containsKey("description"));
        assertFalse(result.containsKey("sellerId"));
        assertFalse(json.writeValueAsString(result).contains("synthetic-private"));
        var previous = new java.util.LinkedHashMap<String,Object>(Map.of("description", "existing", "sellerId", "old"));
        previous.putAll(result);
        assertEquals("existing", previous.get("description"));
        assertEquals("old", previous.get("sellerId"));
    }
}
