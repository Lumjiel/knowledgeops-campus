package com.enterprise.iqk.config;

import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.ingestion.IngestionProcessResult;
import com.enterprise.iqk.ingestion.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 预置语料自动导入（spec-D 坑 3 方案 A）：仅当 app.seed.enabled=true 时激活，
 * 供 docker-compose.lite.yml 在容器首启时把挂载目录（默认 /data/seed）下的 PDF
 * 同步导入向量索引。不开放上传接口的部署形态靠它备料。
 *
 * 幂等性：simple 后端下索引快照文件已存在则整轮跳过；否则依赖 submitPdf 的
 * 租户级幂等键去重，processQueuedJob 的 claim 语义防重复处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class SeedDataRunner implements CommandLineRunner {

    private final IngestionService ingestionService;
    private final VectorStoreProperties vectorStoreProperties;

    @Value("${app.seed.source-dir:/data/seed}")
    private String sourceDir;

    @Value("${app.seed.chat-id:campus-rules}")
    private String chatId;

    @Value("${app.seed.tenant-id:public}")
    private String tenantId;

    @Override
    public void run(String... args) {
        boolean simpleBackend = "simple".equalsIgnoreCase(vectorStoreProperties.getBackend());
        if (simpleBackend && Files.exists(Path.of(vectorStoreProperties.getSimpleStorePath()))) {
            log.info("[seed] vector snapshot {} already exists, skip seeding",
                    vectorStoreProperties.getSimpleStorePath());
            return;
        }
        Path dir = Path.of(sourceDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[seed] source dir {} not mounted or missing, skip seeding", sourceDir);
            return;
        }
        List<Path> pdfs = listPdfs(dir);
        if (pdfs.isEmpty()) {
            log.warn("[seed] no PDF found under {}, nothing to seed", sourceDir);
            return;
        }
        log.info("[seed] seeding {} PDF(s) from {} into tenant={}, chatId={}", pdfs.size(), dir, tenantId, chatId);
        for (Path pdf : pdfs) {
            seedOne(pdf);
        }
        log.info("[seed] done; snapshot will persist to {} and survive container restarts",
                vectorStoreProperties.getSimpleStorePath());
    }

    private void seedOne(Path pdf) {
        String fileName = pdf.getFileName().toString();
        try {
            byte[] bytes = Files.readAllBytes(pdf);
            MultipartFile file = new PdfUploadAdapter(fileName, bytes);
            var job = ingestionService.submitPdf(tenantId, chatId, file, null, "seed");
            IngestionProcessResult result = ingestionService.processQueuedJob(job.getJobId(), tenantId, "seed");
            if (result.getStatus() == IngestionJobStatus.SUCCEEDED) {
                log.info("[seed] {} -> {} ({})", fileName, job.getJobId(), result.getStatus());
            } else {
                log.error("[seed] {} -> {} finished with status={}, error={}",
                        fileName, job.getJobId(), result.getStatus(), result.getErrorMessage());
            }
        } catch (Exception e) {
            // 单个文件失败不阻断其余语料导入；失败详情已在日志里，可清理数据卷后重跑
            log.error("[seed] failed to seed {}: {}", fileName, e.toString());
        }
    }

    private List<Path> listPdfs(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.error("[seed] cannot list {}: {}", dir, e.toString());
            return List.of();
        }
    }

    /**
     * 最小 MultipartFile 适配器：把本地文件包装成 IngestionService.submitPdf 需要的形态。
     * 只为种子导入服务，不参与上传链路。
     */
    static class PdfUploadAdapter implements MultipartFile {
        private final String originalFilename;
        private final byte[] bytes;

        PdfUploadAdapter(String originalFilename, byte[] bytes) {
            this.originalFilename = originalFilename;
            this.bytes = bytes;
        }

        @Override
        public String getName() {
            return "seed";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "application/pdf";
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
