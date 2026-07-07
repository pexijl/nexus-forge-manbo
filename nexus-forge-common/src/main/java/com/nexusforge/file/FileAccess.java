package com.nexusforge.file;

/**
 * 文件访问权限
 * <p>决定文件是否公开可读。</p>
 */
public enum FileAccess {
    /**
     * 公开读，bucket 需 public-read policy
     */
    PUBLIC,
    /**
     * 私有，bucket 需 private policy
     */
    PRIVATE
}
