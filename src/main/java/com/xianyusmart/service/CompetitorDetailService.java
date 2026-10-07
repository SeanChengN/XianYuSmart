package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.exception.CompetitorDetailException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.utils.XianyuApiCallUtils;
import com.xianyusmart.utils.PlatformRestrictionGuidance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded read-only detail cache and account-wide request pacing. */
@Service
public class CompetitorDetailService {
    public static final String SNAPSHOT_KEY = "competitorSnapshot";
    private static final long TTL_MS = 3_600_000;
    private static final long INTERVAL_MS = 3_000;
    private final AccountService accounts;
    private final XianyuAccountMapper accountMapper;
    private final XianyuApiCallUtils api;
    private final RiskControlService risk;
    private final CompetitorOrderPreviewParser previewParser;
    private final Clock clock;
    private final Pause pause;
    private final Map<Key, Entry> cache = new LinkedHashMap<>();
    private final ConcurrentHashMap<Key, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AccountKey, Gate> gates = new ConcurrentHashMap<>();

    @Autowired
    public CompetitorDetailService(AccountService accounts, XianyuAccountMapper accountMapper, XianyuApiCallUtils api,
                                   RiskControlService risk, ObjectMapper mapper) {
        this(accounts, accountMapper, api, risk, mapper, Clock.systemUTC(), Thread::sleep);
    }

    CompetitorDetailService(AccountService accounts, XianyuAccountMapper accountMapper, XianyuApiCallUtils api,
                            RiskControlService risk, ObjectMapper mapper, Clock clock, Pause pause) {
        this.accounts = accounts;
        this.accountMapper = accountMapper;
        this.api = api;
        this.risk = risk;
        this.previewParser = new CompetitorOrderPreviewParser(mapper);
        this.clock = clock;
        this.pause = pause;
    }

    public Map<String, Object> fetch(Long accountId, String itemId, boolean forceRefresh) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || accountId == null) throw new IllegalArgumentException("请选择用于采集的账号");
        if (itemId == null || !itemId.matches("\\d{8,64}")) throw new IllegalArgumentException("商品ID格式无效");
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null || !tenantId.equals(account.getTenantId())) {
            throw new IllegalArgumentException("采集账号不存在或无权访问");
        }
        Key key = new Key(tenantId, accountId, itemId);
        if (!forceRefresh) {
            synchronized (cache) {
                Entry entry = cache.get(key);
                if (entry != null && entry.expiresAt > clock.millis()) return new LinkedHashMap<>(entry.data);
                cache.remove(key);
            }
        }
        CompletableFuture<Map<String, Object>> owner = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing = inFlight.putIfAbsent(key, owner);
        if (existing != null) {
            try { return new LinkedHashMap<>(existing.join()); }
            catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                throw e;
            }
        }
        try {
            // A preceding owner may have finished between the initial cache read and registration.
            if (!forceRefresh) {
                synchronized (cache) {
                    Entry entry = cache.get(key);
                    if (entry != null && entry.expiresAt > clock.millis()) {
                        owner.complete(entry.data);
                        return new LinkedHashMap<>(entry.data);
                    }
                }
            }
            Gate gate = gates.computeIfAbsent(new AccountKey(tenantId, accountId), ignored -> new Gate());
            Map<String, Object> data;
            synchronized (gate) {
                assertAllowed(accountId);
                long wait = gate.nextAllowedAt - clock.millis();
                if (wait > 0) pause.sleep(wait);
                assertAllowed(accountId);
                String cookie = accounts.getCookieByAccountId(accountId);
                if (cookie == null || cookie.isBlank()) throw new IllegalStateException("账号Cookie不可用");
                gate.nextAllowedAt = clock.millis() + INTERVAL_MS;
                XianyuApiCallUtils.ApiCallResult result = api.callApiWithRetry(accountId,
                        "mtop.taobao.idle.trade.order.render", "7.0", Map.of("itemId", itemId), cookie,
                        Map.of("Referer", "https://www.goofish.com/create-order?itemId=" + itemId),
                        Map.of("spm_cnt", "a21ybx.create-order.0.0"));
                gate.nextAllowedAt = clock.millis() + INTERVAL_MS;
                if (!result.isSuccess()) {
                    throw new CompetitorDetailException("平台确认页规格获取失败: " + result.getErrorMessage(),
                            result.isPlatformRestricted() || result.isGuardBlocked(), result.getRiskReason());
                }
                data = previewParser.parse(result.getResponse(), itemId, clock.instant());
            }
            synchronized (cache) {
                cache.entrySet().removeIf(entry -> entry.getValue().expiresAt <= clock.millis());
                if (cache.size() >= 512) cache.remove(cache.keySet().iterator().next());
                cache.put(key, new Entry(Map.copyOf(data), clock.millis() + TTL_MS));
            }
            owner.complete(data);
            return new LinkedHashMap<>(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("竞品详情请求已取消");
            owner.completeExceptionally(failure);
            throw failure;
        } catch (RuntimeException | Error e) {
            owner.completeExceptionally(e);
            throw e;
        } finally { inFlight.remove(key, owner); }
    }

    public void assertAllowed(Long accountId) {
        RiskControlService.GuardStatus status = risk.getStatus(accountId);
        if (status != null && status.state() == RiskControlService.GuardState.CIRCUIT_OPEN) {
            throw new CompetitorDetailException(PlatformRestrictionGuidance.message(status.reason(), status.remainingSeconds()),
                    true, status.reason());
        }
    }

    static Map<String, Object> failedSnapshot(Map<String, Object> previous, String itemId,
                                              RuntimeException failure, String attemptedAt) {
        boolean blocked = failure instanceof CompetitorDetailException detail && detail.isValidationRequired();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Object old = previous.get(SNAPSHOT_KEY);
        if (old instanceof Map<?, ?> values) values.forEach((key, value) -> snapshot.put(String.valueOf(key), value));
        else if (old instanceof CompetitorSkuSnapshot value) {
            snapshot.put("displayPrice", value.displayPrice());
            snapshot.put("source", value.source());
            snapshot.put("capturedAt", value.capturedAt());
            snapshot.put("skus", value.skus());
        }
        snapshot.put("itemId", itemId);
        snapshot.put("status", blocked ? "BLOCKED" : "FAILED");
        snapshot.put("message", failure.getMessage());
        snapshot.put("attemptedAt", attemptedAt);
        snapshot.put("stale", snapshot.get("capturedAt") != null);
        snapshot.putIfAbsent("skus", java.util.List.of());
        if (failure instanceof CompetitorDetailException detail) snapshot.put("reason", detail.getReason());
        return snapshot;
    }

    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }
    private record Key(Long tenantId, Long accountId, String itemId) { }
    private record AccountKey(Long tenantId, Long accountId) { }
    private record Entry(Map<String, Object> data, long expiresAt) { }
    private static final class Gate { long nextAllowedAt; }
}
