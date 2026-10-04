package com.github.guoyyit.telemetryprivacypipelinejava.export;

/**
 * 调用方身份：id 与角色；仅 PRIVACY_ADMIN 角色可查看未脱敏原始值。
 */
public record Caller(String id, String role) {

    public static final String ROLE_PRIVACY_ADMIN = "PRIVACY_ADMIN";
    public static final String ROLE_ANALYST = "ANALYST";
    public static final String ROLE_SERVICE = "SERVICE";

    public boolean isPrivacyAdmin() {
        return ROLE_PRIVACY_ADMIN.equals(role);
    }
}
