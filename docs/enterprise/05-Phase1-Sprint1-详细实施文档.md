# Phase 1 Sprint 1 详细实施文档

> 本文档是 Phase 1 Sprint 1（第 1-2 周）的详细实施指南，包含可执行的代码、配置、SQL 和验证步骤。
>
> **关联文档：**
> - `01-项目现有技术与功能分析.md` - 现有技术栈
> - `04-实际实施开发计划.md` - Phase 1 整体计划
>
> **文档版本：** v1.0
> **创建日期：** 2026-04-19
> **Sprint 周期：** 第 1-2 周

---

## 一、Sprint 1 目标

**交付物：**
1. pgvector 扩展集成到 PostgreSQL
2. LangChain4j + pgvector 依赖添加到 pom.xml
3. Embedding 模型配置（支持 MiniMax/本地切换）
4. 向量检索基础设施代码（VectorService）
5. 基本的向量 CRUD 功能

**验收标准：**
- pgvector 扩展在 PostgreSQL 中可用
- 向量插入和相似度查询功能正常
- 单元测试覆盖率达到 80%+

---

## 二、技术准备

### 2.1 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21 | LTS 版本 |
| Spring Boot | 3.2.3 | 当前项目版本 |
| PostgreSQL | 16 | 带 pgvector 扩展 |
| Maven | 3.9+ | 构建工具 |

### 2.2 依赖版本锁定

| 依赖 | 版本 | 来源 |
|------|------|------|
| langchain4j | 1.0.0 | Maven Central |
| langchain4j-open-ai | 1.0.0 | Maven Central |
| pgvector-java | 0.1.0 | Maven Central |

---

## 三、详细实施步骤

### 步骤 1：更新 Docker Compose 配置

**文件：** `docker-compose.yml`

**变更：** 为 PostgreSQL 添加 pgvector 扩展支持

```yaml
services:
  postgres:
    image: ${POSTGRES_IMAGE:-postgres:16-alpine}
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: skillhub
      POSTGRES_USER: skillhub
      POSTGRES_PASSWORD: skillhub_dev
    # 新增：加载 pgvector 扩展
    command: postgres -c shared_preload_libraries=vector
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U skillhub"]
      interval: 5s
      timeout: 5s
      retries: 5
```

**执行命令：**
```bash
cd D:/person_ai_projects/first_version/skillhub
docker-compose down
docker-compose up -d postgres
```

**验证：**
```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "SELECT * FROM pg_extension WHERE extname = 'vector';"
```

**预期输出：**
```
 name   | nspname | owner | foid | relkind | is_default
-------+---------+-------+------+---------+------------
 vector | public  |     8 |    0 | s       | f
```

---

### 步骤 2：更新 pom.xml 依赖

**文件：** `server/skillhub-domain/pom.xml`

在 `<dependencies>` 中添加：

```xml
<!-- LangChain4j Core -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- LangChain4j OpenAI -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- pgvector JDBC -->
<dependency>
    <groupId>io.github.segpcx</groupId>
    <artifactId>pgvector-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

**执行命令：**
```bash
cd server/skillhub-domain
../../../mvnw dependency:resolve -DincludeScope=compile
```

**验证：**
```bash
../../../mvnw dependency:tree | grep -E "langchain4j|pgvector"
```

---

### 步骤 3：创建 Flyway 数据库迁移

**文件：** `server/skillhub-app/src/main/resources/db/migration/V1__add_pgvector_support.sql`

```sql
-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- skills 表新增 embedding 向量字段
ALTER TABLE skills ADD COLUMN embedding vector(1536);

-- 创建 HNSW 索引加速向量检索
-- HNSW 索引特点：构建慢、查询快、内存占用较高
-- 参数说明：
--   m = 16: 每个节点的邻居数，越大越精确但越占内存
--   ef_construction = 64: 构建时的动态列表大小，越大越精确但越慢
CREATE INDEX idx_skills_embedding_hnsw ON skills
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- 创建 IVFFlat 索引（备选，适合数据量小的场景）
-- IVFFlat 索引特点：构建快、查询中等、内存占用中等
-- 参数说明：
--   lists = 100: 聚类中心数，数据量小可降低
-- 注意：IVFFlat 需要先对数据进行归一化
-- CREATE INDEX idx_skills_embedding_ivfflat ON skills
-- USING ivfflat (embedding vector_cosine_ops)
-- WITH (lists = 100);
```

**执行命令：**
```bash
# 重启应用以触发 Flyway 迁移
cd ../..
docker-compose restart skillhub-app
# 或者本地开发模式
make dev-server-restart
```

**验证：**
```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "\d skills"
```

**预期输出应包含：**
```
embedding       | vector(1536)
```

---

### 步骤 4：创建 Embedding 配置类

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/embedding/EmbeddingModelConfig.java`

```java
package com.skillhub.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

/**
 * Embedding 模型配置
 * 支持 MiniMax（国内）和本地模型两种模式
 * MiniMax 使用 OpenAI 兼容 API
 */
@Configuration
public class EmbeddingModelConfig {

    @Value("${skillhub.embedding.model:minimax}")
    private String embeddingModel;

    @Value("${skillhub.embedding.minimax.api-key:}")
    private String minimaxApiKey;

    @Value("${skillhub.embedding.minimax.base-url:}")
    private String minimaxBaseUrl;

    @Value("${skillhub.embedding.minimax.model:emb-0}")
    private String minimaxModel;

    @Value("${skillhub.embedding.minimax.dimensions:1024}")
    private int dimensions;

    /**
     * Embedding 模型 Bean
     * 根据配置选择 MiniMax 或本地模型
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        if ("minimax".equalsIgnoreCase(embeddingModel)) {
            // MiniMax 使用 OpenAI 兼容 API
            return OpenAiEmbeddingModel.builder()
                .apiKey(minimaxApiKey)
                .baseUrl(resolveBaseUrl())
                .model(minimaxModel)
                .dimensions(dimensions)
                .build();
        }
        // 本地模型支持（Ollama 等）- 后续实现
        throw new IllegalStateException("Unsupported embedding model: " + embeddingModel);
    }

    private String resolveBaseUrl() {
        if (minimaxBaseUrl != null && !minimaxBaseUrl.isBlank()) {
            return minimaxBaseUrl;
        }
        return "https://api.minimaxi.com/v1";  // MiniMax 中国端点
    }
}
```

---

### 步骤 5：创建 Embedding Service

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/embedding/EmbeddingService.java`

```java
package com.skillhub.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量嵌入服务
 * 负责文本向量化
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 将单个文本转换为向量
     *
     * @param text 待嵌入的文本
     * @return 向量数组
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[embeddingModel.dimensions()];
        }
        EmbeddingResult result = embeddingModel.embed(text);
        return result.contentAsVector();
    }

    /**
     * 批量将文本转换为向量
     *
     * @param texts 待嵌入的文本列表
     * @return 向量数组列表
     */
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream()
            .map(this::embed)
            .toList();
    }

    /**
     * 获取 Embedding 模型的向量维度
     */
    public int dimensions() {
        return embeddingModel.dimensions();
    }
}
```

---

### 步骤 6：创建 VectorSearch Service

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/embedding/VectorSearchService.java`

```java
package com.skillhub.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.segpcx.types.FloatArrayType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 向量检索服务
 * 负责向量插入和相似度查询
 */
@Service
public class VectorSearchService {

    private final EntityManager entityManager;
    private final EmbeddingService embeddingService;

    public VectorSearchService(EntityManager entityManager, EmbeddingService embeddingService) {
        this.entityManager = entityManager;
        this.embeddingService = embeddingService;
    }

    /**
     * 为技能生成并存储向量
     *
     * @param skillId 技能ID
     * @param text     要向量化的文本（通常是技能的 name + description）
     */
    @Transactional
    public void indexSkill(Long skillId, String text) {
        float[] embedding = embeddingService.embed(text);

        entityManager.createNativeQuery(
            "UPDATE skills SET embedding = ? WHERE id = ?"
        ).setParameter(1, FloatArrayType.toPGvector(embedding))
         .setParameter(2, skillId)
         .executeUpdate();
    }

    /**
     * 语义搜索技能（仅向量检索）
     *
     * @param query 查询文本
     * @param limit 返回数量
     * @return 技能ID列表（按相似度降序）
     */
    @SuppressWarnings("unchecked")
    public List<Long> semanticSearch(String query, int limit) {
        float[] queryEmbedding = embeddingService.embed(query);

        Query nativeQuery = entityManager.createNativeQuery(
            """
            SELECT id, 1 - (embedding <=> :query) as similarity
            FROM skills
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> :query
            LIMIT :limit
            """
        ).setParameter("query", FloatArrayType.toPGvector(queryEmbedding))
         .setParameter("limit", limit);

        List<Object[]> results = nativeQuery.getResultList();
        return results.stream()
            .map(row -> ((Number) row[0]).longValue())
            .toList();
    }

    /**
     * 获取技能向量的相似度
     *
     * @param skillId 技能ID
     * @param query   查询文本
     * @return 相似度分数（0-1，越高越相似）
     */
    public double getSimilarity(Long skillId, String query) {
        float[] queryEmbedding = embeddingService.embed(query);

        Object result = entityManager.createNativeQuery(
            """
            SELECT 1 - (embedding <=> :query) as similarity
            FROM skills
            WHERE id = :skillId AND embedding IS NOT NULL
            """
        ).setParameter("query", FloatArrayType.toPGvector(queryEmbedding))
         .setParameter("skillId", skillId)
         .getSingleResult();

        return ((Number) result).doubleValue();
    }

    /**
     * 检查技能是否有向量
     */
    public boolean hasEmbedding(Long skillId) {
        Long count = (Long) entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM skills WHERE id = :skillId AND embedding IS NOT NULL"
        ).setParameter("skillId", skillId)
         .getSingleResult();
        return count > 0;
    }

    /**
     * 删除技能向量
     */
    @Transactional
    public void removeEmbedding(Long skillId) {
        entityManager.createNativeQuery(
            "UPDATE skills SET embedding = NULL WHERE id = ?"
        ).setParameter(1, skillId)
         .executeUpdate();
    }
}
```

---

### 步骤 7：创建配置属性类

**文件：** `server/skillhub-app/src/main/java/com/skillhub/app/config/EmbeddingProperties.java`

```java
package com.skillhub.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding 配置属性
 */
@Component
@ConfigurationProperties(prefix = "skillhub.embedding")
public class EmbeddingProperties {

    /**
     * 嵌入模型类型：minimax / local
     */
    private String model = "minimax";

    /**
     * MiniMax 配置（国内使用，OpenAI 兼容 API）
     */
    private MiniMax minimax = new MiniMax();

    /**
     * 本地模型配置
     */
    private Local local = new Local();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public MiniMax getMinimax() {
        return minimax;
    }

    public void setMinimax(MiniMax minimax) {
        this.minimax = minimax;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }

    public static class MiniMax {
        private String apiKey;
        private String baseUrl = "https://api.minimaxi.com/v1";
        private String model = "emb-0";
        private int dimensions = 1024;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }

    public static class Local {
        private String modelPath;
        private int batchSize = 32;

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
```

---

### 步骤 8：更新 application-local.yml 配置

**文件：** `server/skillhub-app/src/main/resources/application-local.yml`

在文件末尾添加：

```yaml
skillhub:
  embedding:
    model: ${EMBEDDING_MODEL:minimax}
    minimax:
      api-key: ${MINIMAX_API_KEY:}
      base-url: ${MINIMAX_API_BASE_URL:https://api.minimaxi.com/v1}
      model: emb-0
      dimensions: 1024
    local:
      model-path: ${LOCAL_EMBEDDING_MODEL_PATH:}
      batch-size: 32
```

---

### 步骤 9：创建单元测试

**文件：** `server/skillhub-domain/src/test/java/com/skillhub/embedding/EmbeddingServiceTest.java`

```java
package com.skillhub.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingResult;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        when(embeddingModel.dimensions()).thenReturn(1536);
        embeddingService = new EmbeddingService(embeddingModel);
    }

    @Test
    void embed_shouldReturnVector_whenTextIsValid() {
        // Given
        String text = "Java Spring Boot Development";
        float[] expectedVector = new float[]{0.1f, 0.2f, 0.3f};
        Embedding embedding = Embedding.from(expectedVector);

        when(embeddingModel.embed(text)).thenReturn(EmbeddingResult.builder()
            .content(embedding)
            .tokenCount(3)
            .build());

        // When
        float[] result = embeddingService.embed(text);

        // Then
        assertNotNull(result);
        assertEquals(1536, result.length);
    }

    @Test
    void embed_shouldReturnZeroVector_whenTextIsBlank() {
        // When
        float[] result = embeddingService.embed("");

        // Then
        assertNotNull(result);
        assertEquals(1536, result.length);
        assertTrue(result[0] == 0f);
    }

    @Test
    void embedBatch_shouldReturnVectors_whenTextsAreValid() {
        // Given
        List<String> texts = List.of("Java", "Python", "Go");
        when(embeddingModel.embed(anyString())).thenReturn(
            EmbeddingResult.builder()
                .content(Embedding.from(new float[1536]))
                .tokenCount(1)
                .build()
        );

        // When
        List<float[]> results = embeddingService.embedBatch(texts);

        // Then
        assertNotNull(results);
        assertEquals(3, results.size());
    }

    @Test
    void dimensions_shouldReturnCorrectValue() {
        // When
        int dims = embeddingService.dimensions();

        // Then
        assertEquals(1536, dims);
    }
}
```

**文件：** `server/skillhub-domain/src/test/java/com/skillhub/embedding/VectorSearchServiceTest.java`

```java
package com.skillhub.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingResult;
import dev.langchain4j.data.embedding.Embedding;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private EmbeddingService embeddingService;

    private VectorSearchService vectorSearchService;

    @BeforeEach
    void setUp() {
        vectorSearchService = new VectorSearchService(entityManager, embeddingService);
    }

    @Test
    void hasEmbedding_shouldReturnTrue_whenEmbeddingExists() {
        // Given
        Long skillId = 1L;
        when(entityManager.createNativeQuery(anyString()))
            .thenReturn(mock(jakarta.persistence.Query.class));

        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);

        // When
        boolean result = vectorSearchService.hasEmbedding(skillId);

        // Then
        assertTrue(result);
    }
}
```

---

## 四、环境变量配置

### 4.1 本地开发环境变量

**文件：** `.env.local`（创建或更新）

```bash
# Embedding 配置
EMBEDDING_MODEL=minimax
MINIMAX_API_KEY=your-api-key-here
MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1

# 如果使用本地模型
# EMBEDDING_MODEL=local
```

### 4.2 验证配置是否生效

```bash
# 启动本地开发环境
make dev-all

# 检查应用日志是否包含以下内容
# Embedding model initialized: minimax, dimensions: 1024
```

---

## 五、验证测试

### 5.1 手动验证步骤

**步骤 1：验证 pgvector 扩展**
```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "
CREATE EXTENSION IF NOT EXISTS vector;
SELECT * FROM pg_extension WHERE extname = 'vector';
"
```

**步骤 2：验证向量字段**
```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "
SELECT column_name, data_type FROM information_schema.columns
WHERE table_name = 'skills' AND column_name = 'embedding';
"
```

**步骤 3：验证 HNSW 索引**
```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "
SELECT indexname, indexdef FROM pg_indexes
WHERE tablename = 'skills' AND indexname LIKE '%embedding%';
"
```

**步骤 4：运行单元测试**
```bash
cd server
./mvnw test -Dtest=EmbeddingServiceTest,VectorSearchServiceTest
```

### 5.2 API 验证（可选）

如果需要通过 API 验证，创建临时测试端点：

```java
@RestController
@RequestMapping("/api/v1/test")
public class TestController {

    @Autowired
    private VectorSearchService vectorSearchService;

    @GetMapping("/embedding/{skillId}")
    public Result<Boolean> hasEmbedding(@PathVariable Long skillId) {
        return Result.success(vectorSearchService.hasEmbedding(skillId));
    }

    @PostMapping("/embedding/{skillId}/index")
    public Result<Void> indexSkill(@PathVariable Long skillId, @RequestParam String text) {
        vectorSearchService.indexSkill(skillId, text);
        return Result.success();
    }

    @GetMapping("/embedding/search")
    public Result<List<Long>> search(@RequestParam String query, @RequestParam(defaultValue = "10") int limit) {
        return Result.success(vectorSearchService.semanticSearch(query, limit));
    }
}
```

---

## 六、常见问题排查

### 问题 1：pgvector 扩展未安装

**症状：**
```
ERROR: could not access file "$libdir/vector": No such file or directory
```

**解决：**
```bash
# 确认 PostgreSQL 镜像支持 pgvector
docker exec -it skillhub-postgres psql -U skillhub -c "SHOW shared_preload_libraries;"
# 应该是: vector

# 如果不是，需要重新启动 PostgreSQL
docker-compose down postgres
docker-compose up -d postgres
```

### 问题 2：向量维度不匹配

**症状：**
```
ERROR: vector dimension mismatch: expected 1536, got 384
```

**解决：**
- 检查 `application.yml` 中 `dimensions` 配置
- 确认使用的 Embedding 模型输出维度一致
- `emb-0` (MiniMax) = 1024 维度

### 问题 3：内存不足（HNSW 索引）

**症状：**
```
ERROR: cannot create HNSW index due to insufficient memory
```

**解决：**
- 降低 HNSW 参数：`m = 8, ef_construction = 32`
- 或切换到 IVFFlat 索引
- 或增加 Docker 内存限制

---

## 七、后续步骤

Sprint 1 完成后，进入 **Sprint 2：多维度分类体系**：

| 任务 | 交付物 |
|------|--------|
| 创建 skill_dimensions 表 | 数据库迁移脚本 |
| 实现维度管理 API | DimensionController |
| 实现技能-维度映射 | SkillDimensionService |
| 前端维度树组件 | DimensionTree.tsx |

---

## 八、代码位置索引

| 文件 | 路径 |
|------|------|
| Docker Compose | `docker-compose.yml` |
| Domain pom.xml | `server/skillhub-domain/pom.xml` |
| 迁移脚本 | `server/skillhub-app/src/main/resources/db/migration/V1__add_pgvector_support.sql` |
| EmbeddingConfig | `server/skillhub-domain/src/main/java/com/skillhub/embedding/EmbeddingModelConfig.java` |
| EmbeddingService | `server/skillhub-domain/src/main/java/com/skillhub/embedding/EmbeddingService.java` |
| VectorSearchService | `server/skillhub-domain/src/main/java/com/skillhub/embedding/VectorSearchService.java` |
| EmbeddingProperties | `server/skillhub-app/src/main/java/com/skillhub/app/config/EmbeddingProperties.java` |
| 应用配置 | `server/skillhub-app/src/main/resources/application-local.yml` |
| 单元测试 | `server/skillhub-domain/src/test/java/com/skillhub/embedding/*Test.java` |

---

**文档结束**
