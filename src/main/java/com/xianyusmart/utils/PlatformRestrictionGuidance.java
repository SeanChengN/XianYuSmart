package com.xianyusmart.utils;

/** Recovery guidance must not imply that every platform rejection provides a captcha. */
public final class PlatformRestrictionGuidance {
    private PlatformRestrictionGuidance() { }

    public static boolean needsVerification(String reason) {
        return "USER_VALIDATE".equals(reason) || "CAPTCHA".equals(reason);
    }

    public static String actionLabel(String reason) {
        return needsVerification(reason) ? "等待人工验证" : "等待人工处理";
    }

    public static String message(String reason, long remainingSeconds) {
        String cooldown = remainingSeconds > 0 ? "，冷却剩余" + remainingSeconds + "秒" : "";
        if (needsVerification(reason)) {
            return "平台要求账号验证" + cooldown
                    + "。请在闲鱼官方页面完成验证，再到连接管理更新Cookie并手动重试；采集不会自动重试。";
        }
        String code = "RGV587".equals(reason) ? "（RGV587）" : "";
        return "平台拒绝了商品详情访问" + code + cooldown
                + "。请等待冷却后手动重试；若闲鱼官方页面出现验证，请完成后到连接管理更新Cookie。"
                + "浏览器验证通过不保证此接口恢复，采集不会自动重试。";
    }
}
