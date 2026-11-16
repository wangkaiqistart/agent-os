package com.agentos.core.tool;

/** 沙箱检查的动作分类契约，具体授权规则由 Sandbox 实现决定，当前尚未接入工具执行链。 */
public enum ActionType {
    /** target 表达待读取的文件目标。 */
    FILE_READ,
    /** target 表达待写入的文件目标。 */
    FILE_WRITE,
    /** target 表达将要执行的命令目标。 */
    SHELL,
    /** target 表达将要访问的 HTTP 目标。 */
    HTTP
}
