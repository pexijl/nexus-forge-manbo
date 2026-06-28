package com.nexusforge.file;

import lombok.Builder;
import lombok.Data;

/**
 * 文件元数据
 */
@Data
@Builder
public class FileMeta {

    /**
     * 文件key
     */
    private String key;
    /**
     * 文件URL
     */
    private String url;
    /**
     * 文件大小
     */
    private long size;
    /**
     * 文件内容类型
     */
    private String contentType;
    /**
     * 文件原始文件名
     */
    private String originalFilename;
    /**
     * 业务类型
     */
    private FileBizType bizType;
}
