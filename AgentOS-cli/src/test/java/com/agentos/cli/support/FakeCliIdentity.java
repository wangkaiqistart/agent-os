package com.agentos.cli.support;

import com.agentos.cli.CliIdentity;

/** 测试中显式控制会话身份，不修改操作系统属性。 */
public record FakeCliIdentity(String userId) implements CliIdentity {}
