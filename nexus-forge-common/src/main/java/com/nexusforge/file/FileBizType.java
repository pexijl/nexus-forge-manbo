package com.nexusforge.file;

/**
 * 文件业务类型
 * <p>决定存储前缀、桶、限大小、CDN 等策略。</p>
 */
public enum FileBizType {
    /**
     * 用户头像
     */
    AVATAR,
    /**
     * 工作区附件
     */
    ATTACHMENT,
    /**
     * AI 图片
     */
    AI_IMAGE,
    /**
     * 工作区导出
     */
    WORK_EXPORT
}