package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProductStatisticsParserTest {
    private final PlatformMarketplaceParser parser = new PlatformMarketplaceParser(new ObjectMapper());

    private String tags(String sold, String want) {
        return "\"fishTags\":{\"r3\":{\"tagList\":[{\"data\":{\"type\":\"text\",\"content\":\"" + sold
                + "\"}},{\"data\":{\"type\":\"gradientImageText\",\"content\":\"" + want + "\"}}]}}";
    }

    private Map<String, Object> search(String card) {
        return parser.parseSearchPageResponse("{\"data\":{\"resultList\":[" + card + "]}}", 30, true).items().getFirst();
    }

    @Test void normalSearchPreservesApproximateProductLabels() {
        var item = search("{\"data\":{\"item\":{\"main\":{\"exContent\":{\"itemId\":\"123\",\"title\":\"商品\","
                + tags("已售1.2万+", "879人想要") + "},\"clickParam\":{\"args\":{\"wantNum\":0}}}}}}");
        assertEquals("已售1.2万+", item.get("soldCountText"));
        assertEquals("879人想要", item.get("wantCountText"));
    }

    @Test void legacySearchKeepsRealZero() {
        var item = search("{\"data\":{\"itemId\":\"123\",\"title\":\"商品\"," + tags("已售0", "0人想要") + "}}");
        assertEquals("已售0", item.get("soldCountText"));
        assertEquals("0人想要", item.get("wantCountText"));
    }

    @Test void shopCardReadsOnlyProductTags() {
        var response = "{\"data\":{\"cardList\":[{\"cardData\":{\"id\":\"123\",\"title\":\"商品\","
                + tags("已售15件", "2.3万人想要") + "}}]}}";
        var item = parser.parseShopPageResponse(response, 30).items().getFirst();
        assertEquals("已售15件", item.get("soldCountText"));
        assertEquals("2.3万人想要", item.get("wantCountText"));
    }

    @Test void excludesSellerTotalsDescriptionsAndTracking() {
        var item = search("""
                {"data":{"itemId":"123","title":"已售999件","desc":"888人想要",
                 "wantNum":999,"soldCount":99,"user":{"soldCount":888,"fishTags":{"r3":{"tagList":[
                  {"data":{"type":"text","content":"已售999"}}]}}},
                 "clickParam":{"args":{"wantNum":888,"soldCount":777}}}}
                """);
        assertNull(item.get("soldCountText"));
        assertNull(item.get("wantCountText"));
    }

    @Test void malformedAndUnrelatedLabelsRemainMissing() {
        for (String label : new String[]{"已售很多", "已售-1", "已售1.2.3万", "累计已售99", "想要879", "999人看过", "已售1件 2人想要"}) {
            var item = search("{\"itemId\":\"123\",\"title\":\"商品\"," + tags(label, label) + "}");
            assertNull(item.get("soldCountText"), label);
            assertNull(item.get("wantCountText"), label);
        }
        assertNull(search("{\"itemId\":\"123\",\"fishTags\":{\"r3\":{\"tagList\":\"broken\"}}}").get("wantCountText"));
        assertNull(search("{\"itemId\":\"123\",\"fishTags\":{\"r3\":{\"tagList\":[null,{\"data\":{\"content\":879}}]}}}").get("wantCountText"));
    }

    @Test void otherSearchCardAndShopExContentAreCompatible() {
        assertEquals("1人想要", search("{\"cardData\":{\"id\":\"123\"," + tags("已售1", "1人想要") + "}}").get("wantCountText"));
        var response = "{\"data\":{\"cardList\":[{\"id\":\"123\",\"exContent\":{" + tags("已售1", "1人想要") + "}}]}}";
        assertEquals("已售1", parser.parseShopPageResponse(response, 30).items().getFirst().get("soldCountText"));
    }
}
