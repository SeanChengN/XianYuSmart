package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.exception.CompetitorDetailException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompetitorDetailServiceTest {
    final AccountService accounts = mock(AccountService.class);
    final XianyuAccountMapper mapper = mock(XianyuAccountMapper.class);
    final XianyuApiCallUtils api = mock(XianyuApiCallUtils.class);
    final RiskControlService risk = mock(RiskControlService.class);
    final MutableClock clock = new MutableClock();
    final List<Long> waits = new ArrayList<>();
    final String item = "1085721375729";
    CompetitorDetailService service;
    final XianyuApiCallUtils.ApiCallResult success = new XianyuApiCallUtils.ApiCallResult(true,
        "{\"data\":{\"components\":[{\"render\":\"itemDescVO\",\"data\":{\"title\":\"sample\",\"price\":\"12.70\"}},{\"render\":\"skuSelectorVO\",\"data\":{\"skuInfoDetails\":{}}}]}}", null, false);

    @BeforeEach void setup() {
        TenantContext.set(1L);
        when(mapper.selectById(anyLong())).thenAnswer(call -> {
            var account = new XianyuAccount(); account.setTenantId(TenantContext.get()); return account;
        });
        when(accounts.getCookieByAccountId(anyLong())).thenReturn("synthetic-cookie");
        when(risk.getStatus(anyLong())).thenReturn(new RiskControlService.GuardStatus(
            RiskControlService.GuardState.NORMAL, 0, 0, null, null));
        when(api.callApiWithRetry(anyLong(), eq("mtop.taobao.idle.trade.order.render"), eq("7.0"), anyMap(), anyString(), anyMap(), anyMap())).thenReturn(success);
        service = new CompetitorDetailService(accounts, mapper, api, risk, new ObjectMapper(), clock,
            millis -> { waits.add(millis); clock.time += millis; });
    }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void cacheOnlyReadsNeverCallPlatformAndRespectScopeExpiryOwnership() {
        assertTrue(service.cached(2L,item).isEmpty());
        verifyNoInteractions(api,accounts,risk);
        service.fetch(2L,item,false);clearInvocations(api,accounts,risk);
        assertTrue(service.cached(2L,item).containsKey("competitorSnapshot"));
        assertTrue(service.cached(3L,item).isEmpty());
        TenantContext.set(4L);assertTrue(service.cached(2L,item).isEmpty());TenantContext.set(1L);
        var foreign=new XianyuAccount();foreign.setTenantId(4L);when(mapper.selectById(9L)).thenReturn(foreign);
        assertThrows(IllegalArgumentException.class,()->service.cached(9L,item));
        clock.time+=3600001;assertTrue(service.cached(2L,item).isEmpty());
        verifyNoInteractions(api,accounts,risk);assertTrue(waits.isEmpty());
    }

    @Test void cacheIsIsolatedExpiresAndForceRefreshStillPaces() {
        service.fetch(2L,item,false); service.fetch(2L,item,false);
        verify(api,times(1)).callApiWithRetry(anyLong(),eq("mtop.taobao.idle.trade.order.render"),eq("7.0"),anyMap(),anyString(),anyMap(),anyMap());
        service.fetch(2L,item,true);
        assertEquals(List.of(3000L), waits);
        service.fetch(3L,item,false);
        TenantContext.set(4L); service.fetch(2L,item,false);
        clock.time += 3_600_001;
        TenantContext.set(1L); service.fetch(2L,item,false);
        verify(api,times(5)).callApiWithRetry(anyLong(),eq("mtop.taobao.idle.trade.order.render"),eq("7.0"),anyMap(),anyString(),anyMap(),anyMap());
    }

    @Test void usesOnlyReadOnlyConfirmationRenderAndPreservesPreviewStatus() {
        var result = service.fetch(2L,item,false);
        assertEquals("ORDER_PREVIEW", result.get("detailStatus"));
        verify(api).callApiWithRetry(eq(2L),eq("mtop.taobao.idle.trade.order.render"),eq("7.0"),
                eq(Map.of("itemId",item)),eq("synthetic-cookie"),
                eq(Map.of("Referer","https://www.goofish.com/create-order?itemId="+item)),
                eq(Map.of("spm_cnt","a21ybx.create-order.0.0")));
        verifyNoMoreInteractions(api);
    }

    @Test void circuitAndOwnershipBlockCallsAndFailuresAreNotCached() {
        var foreign = new XianyuAccount(); foreign.setTenantId(77L);
        when(mapper.selectById(9L)).thenReturn(foreign);
        assertThrows(IllegalArgumentException.class, () -> service.fetch(9L,item,false));
        when(risk.getStatus(2L)).thenReturn(new RiskControlService.GuardStatus(
            RiskControlService.GuardState.CIRCUIT_OPEN,600,600000,"USER_VALIDATE",null));
        var error = assertThrows(CompetitorDetailException.class, () -> service.fetch(2L,item,true));
        assertTrue(error.isValidationRequired()); verifyNoInteractions(api);
        when(risk.getStatus(2L)).thenReturn(new RiskControlService.GuardStatus(RiskControlService.GuardState.NORMAL,0,0,null,null));
        when(api.callApiWithRetry(anyLong(),eq("mtop.taobao.idle.trade.order.render"),eq("7.0"),anyMap(),anyString(),anyMap(),anyMap()))
            .thenReturn(XianyuApiCallUtils.ApiCallResult.platformRestricted("{}","verify","USER_VALIDATE"),success);
        assertThrows(CompetitorDetailException.class, () -> service.fetch(2L,item,false));
        assertNotNull(service.fetch(2L,item,false));
        verify(api,times(2)).callApiWithRetry(anyLong(),eq("mtop.taobao.idle.trade.order.render"),eq("7.0"),anyMap(),anyString(),anyMap(),anyMap());
    }

    @Test void genericRiskDoesNotClaimCaptchaAndBlocksRepeatedRequests() {
        when(risk.getStatus(2L)).thenReturn(new RiskControlService.GuardStatus(
            RiskControlService.GuardState.CIRCUIT_OPEN, 437, 600000, "RGV587", null));
        var error = assertThrows(CompetitorDetailException.class, () -> service.fetch(2L,item,true));
        assertTrue(error.isValidationRequired());
        assertTrue(error.getMessage().contains("RGV587"));
        assertTrue(error.getMessage().contains("437秒"));
        assertFalse(error.getMessage().contains("等待人工验证"));
        assertThrows(CompetitorDetailException.class, () -> service.fetch(2L,item,true));
        verifyNoInteractions(api);
    }

    @Test void concurrentDuplicatesCoalesceAndSameAccountSerializes() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var max = new AtomicInteger(); var calls = new AtomicInteger();
        when(api.callApiWithRetry(anyLong(),eq("mtop.taobao.idle.trade.order.render"),eq("7.0"),anyMap(),anyString(),anyMap(),anyMap())).thenAnswer(call -> {
            max.accumulateAndGet(active.incrementAndGet(),Math::max);
            if (calls.incrementAndGet()==1) { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
            active.decrementAndGet(); return success;
        });
        try (var pool = Executors.newFixedThreadPool(3)) {
            Callable<Map<String,Object>> fetch = () -> { TenantContext.set(1L); try {return service.fetch(2L,item,false);} finally {TenantContext.clear();} };
            var first = pool.submit(fetch); assertTrue(entered.await(5,TimeUnit.SECONDS));
            var second = pool.submit(fetch);
            var other = pool.submit(() -> {TenantContext.set(1L); try {return service.fetch(2L,"698321371327",false);} finally {TenantContext.clear();}});
            release.countDown();
            assertEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)); other.get(5,TimeUnit.SECONDS);
        }
        assertEquals(2,calls.get()); assertEquals(1,max.get()); assertEquals(List.of(3000L),waits);
    }

    static class MutableClock extends Clock {
        volatile long time;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(time);}
        public long millis(){return time;}
    }
}
