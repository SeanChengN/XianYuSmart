package com.xianyusmart.service;

import com.xianyusmart.entity.SysUser;
import com.xianyusmart.interceptor.AccessControlInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectSupplyPermissionTest {
    @Test void directEndpointRequiresOperationsMenuAndWritePermission() throws Exception {
        var service=mock(PlatformPermissionService.class);
        var request=mock(HttpServletRequest.class);
        var response=mock(HttpServletResponse.class);
        var user=new SysUser();
        when(request.getAttribute("currentUser")).thenReturn(user);
        when(request.getRequestURI()).thenReturn("/api/merchant/opportunities/supply");
        when(request.getMethod()).thenReturn("POST");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        var interceptor=new AccessControlInterceptor(service);
        when(service.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_OPERATIONS));
        assertFalse(interceptor.preHandle(request,response,new Object()));
        when(service.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.ACTION_OPERATIONS_WRITE));
        assertFalse(interceptor.preHandle(request,response,new Object()));
        when(service.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_OPERATIONS,PermissionCatalog.ACTION_OPERATIONS_WRITE));
        assertTrue(interceptor.preHandle(request,response,new Object()));
    }
}
