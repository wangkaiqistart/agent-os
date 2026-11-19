package com.agentos.tool.file;

/** 仅携带固定分类，底层异常、绝对路径与正文不得通过异常泄漏。 */
public final class FileToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FileToolException(String category) {
        super(category);
    }
}
