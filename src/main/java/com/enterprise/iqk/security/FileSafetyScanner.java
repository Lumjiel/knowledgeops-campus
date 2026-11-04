package com.enterprise.iqk.security;

import org.springframework.web.multipart.MultipartFile;

/**
 * File: FileSafetyScanner.java
 * <p>
 * 上传文件安全扫描策略（摄取链路提交侧的第一道闸门，见
 * {@code IngestionService.submitPdf}）：不通过时抛异常拒绝受理。
 */
public interface FileSafetyScanner {
    /** 校验上传文件；非法内容抛 {@code IllegalArgumentException} 等运行时异常。 */
    void scan(MultipartFile file);
}
