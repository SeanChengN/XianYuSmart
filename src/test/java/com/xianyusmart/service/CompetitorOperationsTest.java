package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.*;
import com.xianyusmart.exception.CompetitorDetailException;
import com.xianyusmart.mapper.*;
import com.xianyusmart.controller.dto.MerchantResourceReqDTO;
import org.junit.jupiter.api.*;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class CompetitorOperationsTest {
    @Mock MerchantResourceMapper resources;
    @Mock MerchantTaskMapper tasks;
    @Mock MerchantDistributionMapper distributions;
    @Mock XianyuAccountMapper accounts;
    @Mock PlatformPublishService platform;
    @Mock OperationLogService logs;
    @Spy ObjectMapper json = new ObjectMapper();
    @InjectMocks MerchantOperationsService service;
    final String item = "1085721375729";
    final Map<String,Object> snapshot = Map.of("capturedAt","2026-10-01T00:00:00Z","skus",List.of(Map.of("skuId","s","priceCents",1270)),"status","AVAILABLE");
    MerchantResource supply;

    @BeforeEach void setup() throws Exception {
        UserContext.set(1L,"synthetic");
        supply = new MerchantResource(); supply.setId(8L); supply.setTenantId(1L);
        supply.setResourceType("SUPPLY"); supply.setXianyuAccountId(2L); supply.setXyGoodsId(item);
        supply.setName("sample"); supply.setAmount(new BigDecimal("12.70")); supply.setStock(1);
        supply.setDataJson(json.writeValueAsString(Map.of("itemId",item,"description","old details","competitorSnapshot",snapshot)));
    }
    @AfterEach void cleanup(){UserContext.clear();}
    void ownedAccount() {var account = new XianyuAccount(); account.setTenantId(1L); when(accounts.selectById(2L)).thenReturn(account);}

    @Test void importStopsBatchOnValidationAndPreservesPriorFacts() throws Exception {
        ownedAccount();
        when(resources.selectByTenantTypeAndGoodsId(1L,"SUPPLY",item)).thenReturn(supply);
        when(platform.competitorDetail(2L,item,false)).thenThrow(new CompetitorDetailException("verify",true,"USER_VALIDATE"));
        service.importOpportunities(Map.of("xianyuAccountId",2L,"candidates",List.of(
            Map.of("itemId",item,"title","search","description","new search text","competitorSnapshot",Map.of("status","AVAILABLE")),
            Map.of("itemId","698321371327","title","second","price","9.99"))));
        verify(platform,times(1)).competitorDetail(anyLong(),anyString(),anyBoolean());
        var saved = json.readTree(supply.getDataJson());
        assertEquals("old details",saved.path("description").asText());
        assertEquals("BLOCKED",saved.path("competitorSnapshot").path("status").asText());
        assertEquals("2026-10-01T00:00:00Z",saved.path("competitorSnapshot").path("capturedAt").asText());
        assertEquals(1270,saved.path("competitorSnapshot").path("skus").get(0).path("priceCents").asInt());
        assertTrue(saved.path("competitorSnapshot").path("stale").asBoolean());
    }

    @Test void collectValidationStopsAutomaticRetryAndSavesEvidence() throws Exception {
        when(resources.selectById(8L)).thenReturn(supply);
        when(platform.competitorDetail(2L,item,false)).thenThrow(new CompetitorDetailException("verify",true,"USER_VALIDATE"));
        var task = new MerchantTask(); task.setId(4L); task.setTenantId(1L); task.setResourceId(8L);
        task.setTaskType("COLLECT"); task.setXianyuAccountId(2L); task.setAttemptCount(0);
        service.executeTask(task);
        verify(tasks).stopForValidation(eq(4L),contains("等待人工验证"));
        verify(tasks,never()).fail(any(),any(),any()); verify(tasks,never()).defer(any(),any(),any());
        verify(resources).updateById(supply);
        assertEquals("USER_VALIDATE",json.readTree(supply.getDataJson()).path("competitorSnapshot").path("reason").asText());
    }

    @Test void collectGenericRiskStillStopsButDoesNotDemandCaptcha() {
        when(resources.selectById(8L)).thenReturn(supply);
        when(platform.competitorDetail(2L,item,false)).thenThrow(new CompetitorDetailException("RGV587",true,"RGV587"));
        var task = new MerchantTask(); task.setId(4L); task.setTenantId(1L); task.setResourceId(8L);
        task.setTaskType("COLLECT"); task.setXianyuAccountId(2L); task.setAttemptCount(0);
        service.executeTask(task);
        verify(tasks).stopForValidation(eq(4L),contains("等待人工处理"));
        verify(tasks,never()).fail(any(),any(),any());
        verify(tasks,never()).defer(any(),any(),any());
    }

    @Test void previewCollectionKeepsDescriptionSellerAndNameAndMarksPreview() throws Exception {
        var previous = json.readValue(supply.getDataJson(),Map.class);
        previous.put("sellerId","old-seller");
        supply.setDataJson(json.writeValueAsString(previous));
        when(resources.selectById(8L)).thenReturn(supply);
        when(platform.competitorDetail(2L,item,false)).thenReturn(Map.of(
                "itemId",item,"detailStatus","ORDER_PREVIEW","competitorSnapshot",snapshot));
        var task = new MerchantTask(); task.setId(4L); task.setTenantId(1L); task.setResourceId(8L);
        task.setTaskType("COLLECT"); task.setXianyuAccountId(2L); task.setAttemptCount(0);
        service.executeTask(task);
        var saved = json.readTree(supply.getDataJson());
        assertEquals("ORDER_PREVIEW",saved.path("detailStatus").asText());
        assertEquals("old details",saved.path("description").asText());
        assertEquals("old-seller",saved.path("sellerId").asText());
        assertEquals("sample",supply.getName());
    }

    @Test void editingKeepsOnlyServerSnapshotAndChangingIdentityClearsIt() throws Exception {
        ownedAccount(); when(resources.selectById(8L)).thenReturn(supply);
        var request = new MerchantResourceReqDTO(); request.setId(8L); request.setResourceType("SUPPLY");
        request.setName("edited"); request.setXianyuAccountId(2L); request.setXyGoodsId(item);
        request.setData(Map.of("description","edited description","competitorSnapshot",Map.of("capturedAt","forged")));
        service.saveResource(request);
        assertEquals("2026-10-01T00:00:00Z",json.readTree(supply.getDataJson()).path("competitorSnapshot").path("capturedAt").asText());
        request.setXyGoodsId("698321371327"); service.saveResource(request);
        assertFalse(json.readTree(supply.getDataJson()).has("competitorSnapshot"));
        var changed = CompetitorSnapshots.edit(Map.of("competitorSnapshot",snapshot),Map.of(),2L,3L,item,item);
        assertFalse(changed.containsKey("competitorSnapshot"));
        var changedLink = CompetitorSnapshots.edit(Map.of("competitorSnapshot",snapshot,"sourceUrl","https://www.goofish.com/item?id="+item),
            Map.of("sourceUrl","https://www.goofish.com/item?id=698321371327"),2L,2L,item,item);
        assertFalse(changedLink.containsKey("competitorSnapshot"));
    }

    @Test void supplyConversionDoesNotAdoptCompetitorPublishingPriceOrStock() {
        when(resources.selectById(8L)).thenReturn(supply);
        when(resources.insert(any(MerchantResource.class))).thenAnswer(call -> {call.<MerchantResource>getArgument(0).setId(9L);return 1;});
        service.convertSupplyToMaterial(8L);
        var capture = org.mockito.ArgumentCaptor.forClass(MerchantResource.class); verify(resources).insert(capture.capture());
        assertEquals(BigDecimal.ZERO,capture.getValue().getAmount()); assertEquals(0,capture.getValue().getStock());
        assertTrue(capture.getValue().getDataJson().contains("competitorSnapshot"));
    }

    @Test void publishingWithoutOwnStockStopsBeforePlatformPreflight() {
        ownedAccount();
        var request = new HashMap<String,Object>(Map.of("xianyuAccountId",2L,"name","sample",
            "description","sample","images",List.of("https://example.invalid/sample.jpg"),"amount","12.70","dryRun",true));
        assertThrows(IllegalArgumentException.class, () -> service.createPublishPlan(request));
        request.put("stock","1.5");
        assertThrows(IllegalArgumentException.class, () -> service.createPublishPlan(request));
        verifyNoInteractions(platform);
    }

    @Test void batchStartsWithOpenCircuitWithoutMakingDetailCalls() {
        ownedAccount();
        doThrow(new CompetitorDetailException("verify",true,"USER_VALIDATE")).when(platform).assertCollectionAllowed(2L);
        var imported = service.importOpportunities(Map.of("xianyuAccountId",2L,"candidates",List.of(Map.of("itemId",item,"title","search"))));
        verify(platform,never()).competitorDetail(anyLong(),anyString(),anyBoolean());
        assertEquals("BLOCKED",((Map<?,?>)imported.getFirst().getData().get("competitorSnapshot")).get("status"));
    }
}
