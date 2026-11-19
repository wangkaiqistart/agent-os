package com.agentos.tool.file;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 文件工具独立的默认拒绝权限与可收紧资源上限。 */
@ConfigurationProperties("agentos.tools.file")
public record FileToolProperties(
        List<Path> readAllowedPaths,
        List<Path> writeAllowedPaths,
        @DefaultValue(".tool-locks") Path lockRoot,
        @DefaultValue("1048576") int maxFileBytes,
        @DefaultValue("200") int maxResults,
        @DefaultValue("1000") int maxScanEntries,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("10000") int maxResultChars) {
    public FileToolProperties {
        readAllowedPaths = readAllowedPaths == null ? List.of() : List.copyOf(readAllowedPaths);
        writeAllowedPaths = writeAllowedPaths == null ? List.of() : List.copyOf(writeAllowedPaths);
        lockRoot = lockRoot == null ? Path.of(".tool-locks") : lockRoot;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (maxFileBytes < 1
                || maxFileBytes > 1048576
                || maxResults < 1
                || maxResults > 200
                || maxScanEntries < 1
                || maxScanEntries > 1000
                || maxResultChars < 128
                || maxResultChars > 10000
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("文件工具限值不合法");
        }
    }

    public static FileToolProperties defaults(List<Path> reads, List<Path> writes) {
        return new FileToolProperties(
                reads,
                writes,
                Path.of(".tool-locks"),
                1048576,
                200,
                1000,
                Duration.ofSeconds(30),
                10000);
    }
}
