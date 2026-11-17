package com.agentos.cli;

/** 使用当前操作系统账号作为 CLI 使用者标识。 */
public final class SystemCliIdentity implements CliIdentity {
    @Override
    public String userId() {
        String value = System.getProperty("user.name", "unknown");
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
