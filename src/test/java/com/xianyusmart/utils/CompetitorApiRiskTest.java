package com.xianyusmart.utils;

import com.xianyusmart.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CompetitorApiRiskTest {
    @Test void orderPreviewCircuitBlocksNetworkAndRiskNeverRetries() {
        var api = new XianyuApiCallUtils();
        var risk = mock(RiskControlService.class);
        var refresh = mock(CookieRefreshService.class);
        ReflectionTestUtils.setField(api,"riskControlService",risk);
        ReflectionTestUtils.setField(api,"cookieRefreshService",refresh);
        when(risk.getStatus(2L)).thenReturn(new RiskControlService.GuardStatus(RiskControlService.GuardState.CIRCUIT_OPEN,600,600000,"RGV587",null));
        try(var network = mockStatic(XianyuApiUtils.class)) {
            assertTrue(api.callApiWithRetry(2L,"mtop.taobao.idle.trade.order.render","7.0",Map.of(),"synthetic-cookie",null,null).isPlatformRestricted());
            network.verifyNoInteractions();
            when(risk.getStatus(2L)).thenReturn(new RiskControlService.GuardStatus(RiskControlService.GuardState.NORMAL,0,0,null,null));
            when(risk.checkApiWrite(anyLong(),anyString())).thenReturn(new RiskControlService.GuardDecision(true,RiskControlService.GuardState.NORMAL,0,0,null,null));
            when(risk.detectRiskControl(anyMap())).thenReturn(true);
            network.when(() -> XianyuApiUtils.callApiWithHeaders(anyString(),eq("7.0"),anyMap(),anyString(),isNull(),isNull(),isNull(),isNull()))
                    .thenReturn(new XianyuApiUtils.ApiCallResultWithHeaders("{\"ret\":[\"FAIL_SYS_RGV587_ERROR::restricted\"]}",Map.of()));
            assertTrue(api.callApiWithRetry(2L,"mtop.taobao.idle.trade.order.render","7.0",Map.of(),"synthetic-cookie",null,null).isPlatformRestricted());
            network.verify(() -> XianyuApiUtils.callApiWithHeaders(anyString(),eq("7.0"),anyMap(),anyString(),isNull(),isNull(),isNull(),isNull()),times(1));
            verifyNoInteractions(refresh);
        }
    }
    @Test void validationPrecedesTokenRefreshAndDetailCircuitBlocksNetwork() {
        var api = new XianyuApiCallUtils();
        var risk = mock(RiskControlService.class);
        var refresh = mock(CookieRefreshService.class);
        ReflectionTestUtils.setField(api,"riskControlService",risk);
        ReflectionTestUtils.setField(api,"cookieRefreshService",refresh);
        when(risk.getStatus(2L)).thenReturn(new RiskControlService.GuardStatus(RiskControlService.GuardState.NORMAL,0,0,null,null));
        when(risk.checkApiWrite(anyLong(),anyString())).thenReturn(new RiskControlService.GuardDecision(true,RiskControlService.GuardState.NORMAL,0,0,null,null));
        when(risk.detectRiskControl(anyMap())).thenReturn(true);
        try(var network = mockStatic(XianyuApiUtils.class)) {
            network.when(() -> XianyuApiUtils.callApiWithHeaders(anyString(),anyString(),anyMap(),anyString(),isNull(),isNull(),isNull(),isNull()))
                .thenReturn(new XianyuApiUtils.ApiCallResultWithHeaders("{\"ret\":[\"FAIL_SYS_TOKEN_EXOIRED::FAIL_SYS_USER_VALIDATE\"]}",Map.of()));
            assertTrue(api.callApiWithRetry(2L,"mtop.taobao.idle.pc.detail",Map.of("itemId","1085721375729"),"synthetic-cookie").isPlatformRestricted());
            verifyNoInteractions(refresh);
            network.clearInvocations();
            when(risk.getStatus(2L)).thenReturn(new RiskControlService.GuardStatus(RiskControlService.GuardState.CIRCUIT_OPEN,600,600000,"USER_VALIDATE",null));
            assertTrue(api.callApiWithRetry(2L,"mtop.taobao.idle.pc.detail",Map.of(),"synthetic-cookie").isPlatformRestricted());
            network.verifyNoInteractions();
        }
    }

    @Test void rejectedDetailWithSetCookieDoesNotRefreshOrClearCircuit() {
        var api = new XianyuApiCallUtils();
        var risk = mock(RiskControlService.class);
        var refresh = mock(CookieRefreshService.class);
        var accounts = mock(AccountService.class);
        ReflectionTestUtils.setField(api,"riskControlService",risk);
        ReflectionTestUtils.setField(api,"cookieRefreshService",refresh);
        ReflectionTestUtils.setField(api,"accountService",accounts);
        when(risk.getStatus(2L)).thenReturn(
                new RiskControlService.GuardStatus(RiskControlService.GuardState.NORMAL,0,0,null,null),
                new RiskControlService.GuardStatus(RiskControlService.GuardState.CIRCUIT_OPEN,600,600000,"RGV587",null));
        when(risk.checkApiWrite(anyLong(),anyString())).thenReturn(new RiskControlService.GuardDecision(true,RiskControlService.GuardState.NORMAL,0,0,null,null));
        when(risk.detectRiskControl(anyMap())).thenReturn(true);
        when(refresh.clearDuplicateCookies(anyString())).thenAnswer(call -> call.getArgument(0));
        try(var network = mockStatic(XianyuApiUtils.class)) {
            network.when(() -> XianyuApiUtils.callApiWithHeaders(anyString(),anyString(),anyMap(),anyString(),isNull(),isNull(),isNull(),isNull()))
                    .thenReturn(new XianyuApiUtils.ApiCallResultWithHeaders("{\"ret\":[\"FAIL_SYS_RGV587_ERROR::restricted\"]}",
                            Map.of("Set-Cookie",List.of("sample=new; Path=/"))));
            var result = api.callApiWithRetry(2L,"mtop.taobao.idle.pc.detail",Map.of(),"sample=old");
            assertTrue(result.isPlatformRestricted());
            assertEquals("RGV587",result.getRiskReason());
            assertTrue(result.getErrorMessage().contains("600秒"));
            assertFalse(result.getErrorMessage().contains("等待人工验证"));
            verify(accounts).updateCookie(2L,"sample=new");
            verify(risk,never()).clearCircuit(anyLong());
            verify(refresh,never()).refreshCookie(anyLong());
            verify(risk).recordResponse(eq(2L),anyMap());
            network.verify(() -> XianyuApiUtils.callApiWithHeaders(anyString(),anyString(),anyMap(),anyString(),isNull(),isNull(),isNull(),isNull()),times(1));
        }
    }
}
