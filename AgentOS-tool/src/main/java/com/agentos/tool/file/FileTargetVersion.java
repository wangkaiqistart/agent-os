package com.agentos.tool.file;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public record FileTargetVersion(
        String input,
        Path target,
        Path parent,
        Object parentKey,
        boolean exists,
        Object fileKey,
        long size,
        FileTime modified,
        boolean regular,
        boolean directory,
        String safePath) {}
