# Phase 1 Sprint 4 详细实施文档

> 本文档是 Phase 1 Sprint 4（第 9-12 周）的详细实施指南，包含主动提交 RAG（知识库）的完整实现。
>
> **关联文档：**
> - `01-项目现有技术与功能分析.md` - 现有技术栈
> - `04-实际实施开发计划.md` - Phase 1 整体计划
> - `05-Phase1-Sprint1-详细实施文档.md` - Sprint 1 向量基础设施
> - `06-Phase1-Sprint2-详细实施文档.md` - Sprint 2 多维度分类
> - `07-Phase1-Sprint3-详细实施文档.md` - Sprint 3 语义搜索
>
> **文档版本：** v1.0
> **创建日期：** 2026-04-22
> **Sprint 周期：** 第 9-12 周

---

## 一、Sprint 4 目标

**交付物：**
1. 知识库数据库设计（knowledge_libraries, knowledge_chunks 表）
2. 文档解析服务（DocumentParserService）
3. 知识库 CRUD API
4. 文档上传组件
5. RAG 检索 API（`/api/v1/rag/*`）
6. RAG 问答功能

**验收标准：**
- 支持 PDF/DOCX/MD/TXT 格式文档上传
- 自动分段和向量化
- RAG 检索准确率 > 70%
- 单元测试覆盖率达到 80%+

---

## 二、技术准备

### 2.1 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21 | LTS 版本 |
| Spring Boot | 3.2.3 | 当前项目版本 |
| PostgreSQL | 16 | 带 pgvector 扩展 |
| LangChain4j | 1.0.0 | AI 集成框架 |
| Tika | 2.9.x | 文档解析（新增） |

### 2.2 新增依赖

在 `pom.xml` 中添加：

```xml
<!-- Apache Tika 文档解析 -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.1</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>2.9.1</version>
</dependency>
```

---

## 三、数据库变更

**文件：** `server/skillhub-app/src/main/resources/db/migration/V3__add_knowledge_libraries.sql`

```sql
-- 知识库表
CREATE TABLE knowledge_libraries (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    owner_id        BIGINT NOT NULL REFERENCES users(id),
    visibility      VARCHAR(20) DEFAULT 'private',
    tags            TEXT[],
    stats_views     BIGINT DEFAULT 0,
    stats_stars     BIGINT DEFAULT 0,
    stats_items     BIGINT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 知识片段表
CREATE TABLE knowledge_chunks (
    id              BIGSERIAL PRIMARY KEY,
    library_id      BIGINT NOT NULL REFERENCES knowledge_libraries(id) ON DELETE CASCADE,
    title           VARCHAR(500),
    content         TEXT NOT NULL,
    content_hash    VARCHAR(64),
    metadata        JSONB,
    token_count     INTEGER,
    embedding       vector(1536),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 知识库-片段关联表
CREATE TABLE library_chunk_mapping (
    id              BIGSERIAL PRIMARY KEY,
    library_id      BIGINT NOT NULL REFERENCES knowledge_libraries(id) ON DELETE CASCADE,
    chunk_id        BIGINT NOT NULL REFERENCES knowledge_chunks(id) ON DELETE CASCADE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(library_id, chunk_id)
);

-- 创建向量索引
CREATE INDEX idx_knowledge_chunks_embedding_hnsw ON knowledge_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- 创建内容哈希索引（用于去重）
CREATE INDEX idx_knowledge_chunks_hash ON knowledge_chunks(content_hash);

-- 创建分词索引（用于关键词搜索）
CREATE INDEX idx_knowledge_chunks_content_gin ON knowledge_chunks
USING gin (to_tsvector('simple', content));

-- 创建库-片段关联索引
CREATE INDEX idx_lcm_library ON library_chunk_mapping(library_id);
CREATE INDEX idx_lcm_chunk ON library_chunk_mapping(chunk_id);
```

---

## 四、详细实施步骤

### 步骤 1：创建实体类

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/entity/KnowledgeLibrary.java`

```java
package com.skillhub.enterprise.knowledge.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "knowledge_libraries")
public class KnowledgeLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(length = 20)
    private String visibility = "private";

    @Column(columnDefinition = "TEXT[]")
    private List<String> tags;

    @Column(name = "stats_views")
    private Long statsViews = 0L;

    @Column(name = "stats_stars")
    private Long statsStars = 0L;

    @Column(name = "stats_items")
    private Long statsItems = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Long getStatsViews() { return statsViews; }
    public void setStatsViews(Long statsViews) { this.statsViews = statsViews; }
    public Long getStatsStars() { return statsStars; }
    public void setStatsStars(Long statsStars) { this.statsStars = statsStars; }
    public Long getStatsItems() { return statsItems; }
    public void setStatsItems(Long statsItems) { this.statsItems = statsItems; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/entity/KnowledgeChunk.java`

```java
package com.skillhub.enterprise.knowledge.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false)
    private Long libraryId;

    @Column(length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(columnDefinition = "JSONB")
    private Map<String, Object> metadata;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getLibraryId() { return libraryId; }
    public void setLibraryId(Long libraryId) { this.libraryId = libraryId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

---

### 步骤 2：创建 DocumentParserService

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/service/DocumentParserService.java`

```java
package com.skillhub.enterprise.knowledge.service;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);
    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword", "text/plain", "text/markdown", "text/html"
    );
    private static final int MAX_TEXT_LENGTH = 10_000_000;

    private final Tika tika = new Tika();
    private final AutoDetectParser parser = new AutoDetectParser();

    public String parseToText(InputStream inputStream, String filename) {
        try {
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
            Metadata metadata = new Metadata();
            metadata.set(Metadata.RESOURCE_NAME_KEY, filename);
            ParseContext context = new ParseContext();
            parser.parse(inputStream, handler, metadata, context);
            return handler.toString();
        } catch (IOException | TikaException e) {
            log.error("Failed to parse document: {}", filename, e);
            throw new RuntimeException("Failed to parse document: " + filename, e);
        }
    }

    public List<ParsedChunk> chunkByParagraphs(String content, String filename) {
        List<ParsedChunk> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\\n\\s*\\n");
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;

            if (currentChunk.length() + trimmed.length() < 1000) {
                currentChunk.append(trimmed).append("\n\n");
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(createChunk(currentChunk.toString().trim(), filename, chunkIndex++));
                }
                currentChunk = new StringBuilder(trimmed).append("\n\n");
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(createChunk(currentChunk.toString().trim(), filename, chunkIndex));
        }

        return chunks;
    }

    private ParsedChunk createChunk(String content, String filename, int chunkIndex) {
        return new ParsedChunk(
            extractTitle(content, filename, chunkIndex),
            content,
            hashContent(content),
            Map.of("filename", filename, "chunkIndex", chunkIndex)
        );
    }

    private String extractTitle(String content, String filename, int chunkIndex) {
        String firstLine = content.split("\\n")[0].trim();
        if (firstLine.length() > 10 && firstLine.length() < 100) {
            return firstLine;
        }
        return filename + " - 第 " + (chunkIndex + 1) + " 部分";
    }

    private String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record ParsedChunk(String title, String content, String contentHash, Map<String, Object> metadata) {}
}
```

---

### 步骤 3：创建 KnowledgeLibraryService

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/service/KnowledgeLibraryService.java`

```java
package com.skillhub.enterprise.knowledge.service;

import com.skillhub.common.exception.ResourceNotFoundException;
import com.skillhub.enterprise.knowledge.entity.KnowledgeChunk;
import com.skillhub.enterprise.knowledge.entity.KnowledgeLibrary;
import com.skillhub.enterprise.knowledge.repository.KnowledgeChunkRepository;
import com.skillhub.enterprise.knowledge.repository.KnowledgeLibraryRepository;
import com.skillhub.enterprise.search.service.EmbeddingService;
import io.github.segpcx.types.FloatArrayType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class KnowledgeLibraryService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLibraryService.class);

    private final KnowledgeLibraryRepository libraryRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final EntityManager entityManager;
    private final EmbeddingService embeddingService;
    private final DocumentParserService documentParser;

    public KnowledgeLibraryService(
            KnowledgeLibraryRepository libraryRepository,
            KnowledgeChunkRepository chunkRepository,
            EntityManager entityManager,
            EmbeddingService embeddingService,
            DocumentParserService documentParser) {
        this.libraryRepository = libraryRepository;
        this.chunkRepository = chunkRepository;
        this.entityManager = entityManager;
        this.embeddingService = embeddingService;
        this.documentParser = documentParser;
    }

    public KnowledgeLibrary createLibrary(String name, String description, Long ownerId, String visibility) {
        KnowledgeLibrary library = new KnowledgeLibrary();
        library.setName(name);
        library.setDescription(description);
        library.setOwnerId(ownerId);
        library.setVisibility(visibility);
        return libraryRepository.save(library);
    }

    @Transactional(readOnly = true)
    public Page<KnowledgeLibrary> getLibraries(Long ownerId, Pageable pageable) {
        return libraryRepository.findByOwnerId(ownerId, pageable);
    }

    @Transactional(readOnly = true)
    public KnowledgeLibrary getLibraryById(Long libraryId) {
        return libraryRepository.findById(libraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Library not found: " + libraryId));
    }

    public List<KnowledgeChunk> addDocument(Long libraryId, MultipartFile file, Long userId) throws IOException {
        KnowledgeLibrary library = getLibraryById(libraryId);
        String content = documentParser.parseToText(file.getInputStream(), file.getOriginalFilename());
        List<DocumentParserService.ParsedChunk> parsedChunks = documentParser.chunkByParagraphs(content, file.getOriginalFilename());

        List<KnowledgeChunk> savedChunks = new ArrayList<>();
        for (DocumentParserService.ParsedChunk parsed : parsedChunks) {
            if (chunkRepository.existsByLibraryIdAndContentHash(libraryId, parsed.contentHash())) {
                continue;
            }
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setLibraryId(libraryId);
            chunk.setTitle(parsed.title());
            chunk.setContent(parsed.content());
            chunk.setContentHash(parsed.contentHash());
            chunk.setMetadata(parsed.metadata());
            chunk.setTokenCount(estimateTokenCount(parsed.content()));
            float[] embedding = embeddingService.embed(parsed.content());
            chunk.setEmbedding(embedding);
            savedChunks.add(chunkRepository.save(chunk));
        }

        library.setStatsItems(library.getStatsItems() + savedChunks.size());
        libraryRepository.save(library);
        log.info("Added {} chunks to library {} from file {}", savedChunks.size(), libraryId, file.getOriginalFilename());
        return savedChunks;
    }

    @Transactional(readOnly = true)
    public List<ChunkSearchResult> searchLibrary(Long libraryId, String query, int topK) {
        float[] queryEmbedding = embeddingService.embed(query);
        Query nativeQuery = entityManager.createNativeQuery(
            """
            SELECT kc.id, kc.title, kc.content, 1 - (kc.embedding <=> :query) as similarity
            FROM knowledge_chunks kc
            WHERE kc.library_id = :libraryId
            ORDER BY kc.embedding <=> :query
            LIMIT :topK
            """
        );
        nativeQuery.setParameter("query", FloatArrayType.toPGvector(queryEmbedding));
        nativeQuery.setParameter("libraryId", libraryId);
        nativeQuery.setParameter("topK", topK);

        @SuppressWarnings("unchecked")
        List<Object[]> results = nativeQuery.getResultList();

        return results.stream().map(row -> new ChunkSearchResult(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                ((Number) row[3]).doubleValue()
        )).collect(Collectors.toList());
    }

    public void deleteLibrary(Long libraryId, Long userId) {
        KnowledgeLibrary library = getLibraryById(libraryId);
        if (!library.getOwnerId().equals(userId)) {
            throw new SecurityException("Not authorized to delete this library");
        }
        libraryRepository.delete(library);
    }

    private int estimateTokenCount(String text) {
        return (int) Math.ceil(text.length() / 4.0);
    }

    public record ChunkSearchResult(Long chunkId, String title, String content, double similarity) {}
}
```

---

### 步骤 4：创建 Repository

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/repository/KnowledgeLibraryRepository.java`

```java
package com.skillhub.enterprise.knowledge.repository;

import com.skillhub.enterprise.knowledge.entity.KnowledgeLibrary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface KnowledgeLibraryRepository extends JpaRepository<KnowledgeLibrary, Long> {
    Page<KnowledgeLibrary> findByOwnerId(Long ownerId, Pageable pageable);
    Optional<KnowledgeLibrary> findByIdAndOwnerId(Long id, Long ownerId);
}
```

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/repository/KnowledgeChunkRepository.java`

```java
package com.skillhub.enterprise.knowledge.repository;

import com.skillhub.enterprise.knowledge.entity.KnowledgeChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    Page<KnowledgeChunk> findByLibraryId(Long libraryId, Pageable pageable);
    Optional<KnowledgeChunk> findByLibraryIdAndContentHash(Long libraryId, String contentHash);
    boolean existsByLibraryIdAndContentHash(Long libraryId, String contentHash);
    long countByLibraryId(Long libraryId);
}
```

---

### 步骤 5：创建 Controller

**文件：** `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/KnowledgeLibraryController.java`

```java
package com.skillhub.controller.enterprise;

import com.skillhub.common.api.Result;
import com.skillhub.enterprise.knowledge.entity.KnowledgeLibrary;
import com.skillhub.enterprise.knowledge.service.KnowledgeLibraryService;
import com.skillhub.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge/libraries")
public class KnowledgeLibraryController {

    private final KnowledgeLibraryService libraryService;

    public KnowledgeLibraryController(KnowledgeLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @PostMapping
    public Result<KnowledgeLibrary> createLibrary(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "private") String visibility,
            @AuthenticationPrincipal User user) {
        return Result.success(libraryService.createLibrary(name, description, user.getId(), visibility));
    }

    @GetMapping
    public Result<Page<KnowledgeLibrary>> getLibraries(
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        return Result.success(libraryService.getLibraries(user.getId(), pageable));
    }

    @GetMapping("/{libraryId}")
    public Result<KnowledgeLibrary> getLibrary(@PathVariable Long libraryId) {
        return Result.success(libraryService.getLibraryById(libraryId));
    }

    @PostMapping("/{libraryId}/documents")
    public Result<List<KnowledgeLibraryService.ChunkSearchResult>> uploadDocument(
            @PathVariable Long libraryId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user) throws Exception {
        var chunks = libraryService.addDocument(libraryId, file, user.getId());
        return Result.success(chunks.stream().map(c ->
            new KnowledgeLibraryService.ChunkSearchResult(c.getId(), c.getTitle(), c.getContent(), 1.0)
        ).toList());
    }

    @DeleteMapping("/{libraryId}")
    public Result<Void> deleteLibrary(@PathVariable Long libraryId, @AuthenticationPrincipal User user) {
        libraryService.deleteLibrary(libraryId, user.getId());
        return Result.success();
    }
}
```

---

### 步骤 6：创建 RAG Controller

**文件：** `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/RagController.java`

```java
package com.skillhub.controller.enterprise;

import com.skillhub.common.api.Result;
import com.skillhub.enterprise.knowledge.service.KnowledgeLibraryService;
import com.skillhub.enterprise.knowledge.service.KnowledgeLibraryService.ChunkSearchResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final KnowledgeLibraryService libraryService;

    public RagController(KnowledgeLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @PostMapping("/search/{libraryId}")
    public Result<List<ChunkSearchResult>> search(
            @PathVariable Long libraryId,
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int topK) {
        return Result.success(libraryService.searchLibrary(libraryId, query, topK));
    }
}
```

---

### 步骤 7：创建单元测试

**文件：** `server/skillhub-domain/src/test/java/com/skillhub/enterprise/knowledge/service/DocumentParserServiceTest.java`

```java
package com.skillhub.enterprise.knowledge.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentParserServiceTest {

    private final DocumentParserService service = new DocumentParserService();

    @Test
    void chunkByParagraphs_shouldSplitContent() {
        String content = "这是第一段内容。\n\n这是第二段内容。\n\n这是第三段内容。";
        List<DocumentParserService.ParsedChunk> chunks = service.chunkByParagraphs(content, "test.txt");
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2);
    }

    @Test
    void chunkByParagraphs_shouldGenerateUniqueHash() {
        String content = "这是第一段内容。\n\n这是第二段内容。";
        List<DocumentParserService.ParsedChunk> chunks = service.chunkByParagraphs(content, "test.txt");
        assertEquals(chunks.stream().map(DocumentParserService.ParsedChunk::contentHash).distinct().count(),
                     chunks.size(), "All content hashes should be unique");
    }

    @Test
    void chunkByParagraphs_shouldHandleEmptyContent() {
        List<DocumentParserService.ParsedChunk> chunks = service.chunkByParagraphs("", "test.txt");
        assertTrue(chunks.isEmpty());
    }
}
```

---

## 五、前端实现

### 5.1 知识库列表页

**文件：** `web/src/features/knowledge/components/LibraryList.tsx`

```tsx
import { Card, List, Button, Space, Modal, Form, Input, Select, Tag, Upload, message } from 'antd';
import { PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useRequest } from '@tanstack/react-query';
import { knowledgeApi } from '@/services/api';

const { TextArea } = Input;

export const LibraryList: React.FC = () => {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [form] = Form.useForm();

  const { data: libraries, isLoading, refetch } = useRequest({
    queryKey: ['knowledge', 'libraries'],
    queryFn: () => knowledgeApi.getLibraries(),
  });

  const createMutation = useMutation({
    mutationFn: (values: any) => knowledgeApi.createLibrary(values),
    onSuccess: () => {
      message.success('知识库创建成功');
      setIsModalOpen(false);
      form.resetFields();
      refetch();
    },
  });

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Card
        title="我的知识库"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setIsModalOpen(true)}>
            创建知识库
          </Button>
        }
      >
        <List
          loading={isLoading}
          dataSource={libraries?.content || []}
          renderItem={(library: any) => (
            <List.Item
              actions={[
                <Upload
                  key="upload"
                  showUploadList={false}
                  beforeUpload={(file) => {
                    knowledgeApi.uploadDocument(library.id, file).then(() => {
                      message.success('文档上传成功');
                      refetch();
                    });
                    return false;
                  }}
                >
                  <Button icon={<UploadOutlined />}>上传文档</Button>
                </Upload>,
              ]}
            >
              <List.Item.Meta
                title={library.name}
                description={
                  <Space>
                    <Tag>{library.visibility}</Tag>
                    <span>{library.statsItems} 个片段</span>
                  </Space>
                }
              />
            </List.Item>
          )}
        />
      </Card>

      <Modal
        title="创建知识库"
        open={isModalOpen}
        onCancel={() => setIsModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={createMutation.isPending}
      >
        <Form form={form} layout="vertical" onFinish={(values) => createMutation.mutate(values)}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input placeholder="知识库名称" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <TextArea rows={3} placeholder="知识库描述" />
          </Form.Item>
          <Form.Item name="visibility" label="可见性" initialValue="private">
            <Select options={[
              { label: '私有', value: 'private' },
              { label: '共享', value: 'shared' },
              { label: '公开', value: 'public' },
            ]} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
};
```

### 5.2 API 客户端

**文件：** `web/src/services/api/knowledge.ts`

```typescript
import { client } from '../client';
import type { KnowledgeLibrary, Page } from '../types';

export const knowledgeApi = {
  createLibrary: (data: { name: string; description?: string; visibility?: string }) =>
    client.post<KnowledgeLibrary>('/api/v1/knowledge/libraries', null, { params: data }),

  getLibraries: (page = 0, size = 20) =>
    client.get<Page<KnowledgeLibrary>>('/api/v1/knowledge/libraries', { params: { page, size } }),

  getLibrary: (libraryId: number) =>
    client.get<KnowledgeLibrary>(`/api/v1/knowledge/libraries/${libraryId}`),

  uploadDocument: (libraryId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return client.post(`/api/v1/knowledge/libraries/${libraryId}/documents`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  searchLibrary: (libraryId: number, query: string, topK = 10) =>
    client.post<any[]>(`/api/v1/rag/search/${libraryId}`, null, { params: { query, topK } }),

  deleteLibrary: (libraryId: number) =>
    client.delete(`/api/v1/knowledge/libraries/${libraryId}`),
};
```

---

## 六、API 接口汇总

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/v1/knowledge/libraries` | 创建知识库 | 需要 |
| GET | `/api/v1/knowledge/libraries` | 获取知识库列表 | 需要 |
| GET | `/api/v1/knowledge/libraries/{id}` | 获取知识库详情 | 需要 |
| POST | `/api/v1/knowledge/libraries/{id}/documents` | 上传文档 | 需要 |
| DELETE | `/api/v1/knowledge/libraries/{id}` | 删除知识库 | 需要 |
| POST | `/api/v1/rag/search/{libraryId}` | RAG 检索 | 需要 |

---

## 七、验证测试

```bash
# 创建知识库
curl -X POST "http://localhost:8080/api/v1/knowledge/libraries?name=test&description=test"

# 上传文档
curl -X POST -F "file=@/path/to/document.pdf" \
  "http://localhost:8080/api/v1/knowledge/libraries/1/documents"

# RAG 检索
curl -X POST "http://localhost:8080/api/v1/rag/search/1?query=如何配置Spring&topK=5"
```

---

## 八、Phase 1 完成汇总

| Sprint | 周期 | 交付物 |
|--------|------|--------|
| Sprint 1 | 第 1-2 周 | pgvector 集成、Embedding 模型配置、向量基础服务 |
| Sprint 2 | 第 3-4 周 | 多维度分类体系、维度管理 API、前端维度树 |
| Sprint 3 | 第 5-8 周 | 语义搜索、混合搜索、前端搜索框 |
| Sprint 4 | 第 9-12 周 | 知识库管理、文档上传、RAG 检索 |

---

## 九、代码位置索引

| 文件 | 路径 |
|------|------|
| 迁移脚本 | `server/skillhub-app/src/main/resources/db/migration/V3__add_knowledge_libraries.sql` |
| KnowledgeLibrary | `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/entity/KnowledgeLibrary.java` |
| KnowledgeChunk | `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/entity/KnowledgeChunk.java` |
| DocumentParserService | `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/service/DocumentParserService.java` |
| KnowledgeLibraryService | `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/service/KnowledgeLibraryService.java` |
| Repository | `server/skillhub-domain/src/main/java/com/skillhub/enterprise/knowledge/repository/*.java` |
| Controller | `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/KnowledgeLibraryController.java` |
| RagController | `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/RagController.java` |
| 前端 LibraryList | `web/src/features/knowledge/components/LibraryList.tsx` |
| 前端 API | `web/src/services/api/knowledge.ts` |
| 单元测试 | `server/skillhub-domain/src/test/java/com/skillhub/enterprise/knowledge/service/DocumentParserServiceTest.java` |

---

**文档结束**
