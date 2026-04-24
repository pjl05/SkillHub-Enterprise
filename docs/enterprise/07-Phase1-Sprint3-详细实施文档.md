# Phase 1 Sprint 3 详细实施文档

> 本文档是 Phase 1 Sprint 3（第 5-8 周）的详细实施指南，包含语义搜索能力的完整实现。
>
> **关联文档：**
> - `01-项目现有技术与功能分析.md` - 现有技术栈
> - `04-实际实施开发计划.md` - Phase 1 整体计划
> - `05-Phase1-Sprint1-详细实施文档.md` - Sprint 1 向量基础设施
> - `06-Phase1-Sprint2-详细实施文档.md` - Sprint 2 多维度分类
>
> **文档版本：** v1.0
> **创建日期：** 2026-04-22
> **Sprint 周期：** 第 5-8 周

---

## 一、Sprint 3 目标

**交付物：**
1. 向量检索服务增强（EmbeddingService + VectorSearchService）
2. 语义搜索 API（`/api/v1/skills/search/semantic`）
3. 混合搜索 API（`/api/v1/skills/search/hybrid`）
4. 搜索结果排序优化（多维度相关度排序）
5. 前端语义搜索框组件
6. 技能自动向量化（发布/更新时）

**验收标准：**
- 语义搜索延迟 P99 < 500ms
- 搜索结果相关性 > 70%（人工评估）
- 支持混合搜索（向量 + 关键词）
- 单元测试覆盖率达到 80%+

---

## 二、技术准备

### 2.1 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21 | LTS 版本 |
| Spring Boot | 3.2.3 | 当前项目版本 |
| PostgreSQL | 16 | 带 pgvector 扩展（已在 Sprint 1 集成） |
| LangChain4j | 1.0.0 | AI 集成框架（已在 Sprint 1 添加） |

---

## 三、详细实施步骤

### 步骤 1：增强 EmbeddingService

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/search/service/EmbeddingService.java`

```java
package com.skillhub.enterprise.search.service;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[embeddingModel.dimensions()];
        }
        try {
            EmbeddingResult result = embeddingModel.embed(text);
            return result.contentAsVector();
        } catch (Exception e) {
            log.error("Failed to embed text: {}", text.substring(0, Math.min(100, text.length())), e);
            return new float[embeddingModel.dimensions()];
        }
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream().map(this::embed).collect(Collectors.toList());
    }

    public int dimensions() {
        return embeddingModel.dimensions();
    }

    public boolean isAvailable() {
        try {
            embed("health check");
            return true;
        } catch (Exception e) {
            log.warn("Embedding model is not available", e);
            return false;
        }
    }
}
```

---

### 步骤 2：增强 VectorSearchService

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/enterprise/search/service/VectorSearchService.java`

```java
package com.skillhub.enterprise.search.service;

import com.skillhub.domain.skill.entity.Skill;
import com.skillhub.domain.skill.repository.SkillRepository;
import io.github.segpcx.types.FloatArrayType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);
    private final EntityManager entityManager;
    private final EmbeddingService embeddingService;
    private final SkillRepository skillRepository;

    public VectorSearchService(EntityManager entityManager, EmbeddingService embeddingService, SkillRepository skillRepository) {
        this.entityManager = entityManager;
        this.embeddingService = embeddingService;
        this.skillRepository = skillRepository;
    }

    @Transactional
    public void indexSkill(Long skillId, String text) {
        float[] embedding = embeddingService.embed(text);
        entityManager.createNativeQuery(
            "UPDATE skills SET embedding = ? WHERE id = ?"
        ).setParameter(1, FloatArrayType.toPGvector(embedding))
         .setParameter(2, skillId)
         .executeUpdate();
        log.info("Indexed skill {} with embedding vector", skillId);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Skill> semanticSearch(String query, int page, int size) {
        float[] queryEmbedding = embeddingService.embed(query);
        Query nativeQuery = entityManager.createNativeQuery(
            """
            SELECT s.*, 1 - (s.embedding <=> :query) as similarity
            FROM skills s
            WHERE s.embedding IS NOT NULL
            ORDER BY s.embedding <=> :query
            LIMIT :limit OFFSET :offset
            """, Skill.class
        );
        nativeQuery.setParameter("query", FloatArrayType.toPGvector(queryEmbedding));
        nativeQuery.setParameter("limit", size);
        nativeQuery.setParameter("offset", page * size);
        List<Skill> results = nativeQuery.getResultList();

        Query countQuery = entityManager.createNativeQuery("SELECT COUNT(*) FROM skills WHERE embedding IS NOT NULL");
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(results, PageRequest.of(page, size), total);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Skill> hybridSearch(String query, double vectorWeight, int page, int size) {
        float[] queryEmbedding = embeddingService.embed(query);
        double keywordWeight = 1 - vectorWeight;
        Query nativeQuery = entityManager.createNativeQuery(
            """
            SELECT s.*,
                   (COALESCE((1 - (s.embedding <=> :query)), 0) * :vectorWeight +
                    COALESCE(ts_rank(to_tsvector('simple', s.name || ' ' || COALESCE(s.description, '')),
                                    plainto_tsquery('simple', :keyword)), 0) * :keywordWeight) as rank
            FROM skills s
            WHERE s.embedding IS NOT NULL
              AND (s.name ILIKE :keywordPattern OR COALESCE(s.description, '') ILIKE :keywordPattern)
            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
            """, Skill.class
        );
        nativeQuery.setParameter("query", FloatArrayType.toPGvector(queryEmbedding));
        nativeQuery.setParameter("keyword", query);
        nativeQuery.setParameter("keywordPattern", "%" + query + "%");
        nativeQuery.setParameter("vectorWeight", vectorWeight);
        nativeQuery.setParameter("keywordWeight", keywordWeight);
        nativeQuery.setParameter("limit", size);
        nativeQuery.setParameter("offset", page * size);
        List<Skill> results = nativeQuery.getResultList();

        Query countQuery = entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM skills s WHERE s.embedding IS NOT NULL AND (s.name ILIKE :kp OR COALESCE(s.description, '') ILIKE :kp)", Long.class
        );
        countQuery.setParameter("kp", "%" + query + "%");
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(results, PageRequest.of(page, size), total);
    }

    @Transactional(readOnly = true)
    public double getSimilarity(Long skillId, String query) {
        float[] queryEmbedding = embeddingService.embed(query);
        Object result = entityManager.createNativeQuery(
            "SELECT 1 - (embedding <=> :query) as similarity FROM skills WHERE id = :skillId AND embedding IS NOT NULL"
        ).setParameter("query", FloatArrayType.toPGvector(queryEmbedding))
         .setParameter("skillId", skillId)
         .getSingleResult();
        return ((Number) result).doubleValue();
    }

    @Transactional(readOnly = true)
    public boolean hasEmbedding(Long skillId) {
        Long count = (Long) entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM skills WHERE id = :skillId AND embedding IS NOT NULL"
        ).setParameter("skillId", skillId)
         .getSingleResult();
        return count > 0;
    }

    @Transactional
    public void removeEmbedding(Long skillId) {
        entityManager.createNativeQuery("UPDATE skills SET embedding = NULL WHERE id = ?")
            .setParameter(1, skillId).executeUpdate();
    }

    @Transactional
    public long reindexAll() {
        List<Skill> skills = skillRepository.findAll();
        long count = 0;
        for (Skill skill : skills) {
            try {
                String textToIndex = skill.getName() + " " + (skill.getDescription() != null ? skill.getDescription() : "");
                indexSkill(skill.getId(), textToIndex);
                count++;
            } catch (Exception e) {
                log.error("Failed to index skill {}", skill.getId(), e);
            }
        }
        log.info("Reindexed {} skills", count);
        return count;
    }
}
```

---

### 步骤 3：创建搜索 Controller

**文件：** `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/SkillSearchController.java`

```java
package com.skillhub.controller.enterprise;

import com.skillhub.common.api.Result;
import com.skillhub.domain.skill.dto.SkillVO;
import com.skillhub.domain.skill.entity.Skill;
import com.skillhub.enterprise.search.service.VectorSearchService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/skills/search")
public class SkillSearchController {

    private final VectorSearchService vectorSearchService;

    public SkillSearchController(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @GetMapping("/semantic")
    public Result<Page<SkillVO>> semanticSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Skill> skills = vectorSearchService.semanticSearch(query, page, size);
        return Result.success(skills.map(SkillVO::fromEntity));
    }

    @GetMapping("/hybrid")
    public Result<Page<SkillVO>> hybridSearch(
            @RequestParam String query,
            @RequestParam(name = "vector_weight", defaultValue = "0.35") double vectorWeight,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Skill> skills = vectorSearchService.hybridSearch(query, vectorWeight, page, size);
        return Result.success(skills.map(SkillVO::fromEntity));
    }

    @PostMapping("/{skillId}/reindex")
    public Result<Void> reindexSkill(@PathVariable Long skillId) {
        vectorSearchService.indexSkill(skillId, "");
        return Result.success();
    }

    @PostMapping("/reindex-all")
    public Result<BatchIndexResult> reindexAll() {
        long count = vectorSearchService.reindexAll();
        return Result.success(new BatchIndexResult(count));
    }

    @GetMapping("/{skillId}/similarity")
    public Result<Double> getSimilarity(@PathVariable Long skillId, @RequestParam String query) {
        return Result.success(vectorSearchService.getSimilarity(skillId, query));
    }

    @GetMapping("/{skillId}/has-embedding")
    public Result<Boolean> hasEmbedding(@PathVariable Long skillId) {
        return Result.success(vectorSearchService.hasEmbedding(skillId));
    }

    public record BatchIndexResult(long indexedCount) {}
}
```

---

### 步骤 4：创建配置属性

**文件：** `server/skillhub-app/src/main/java/com/skillhub/config/SearchProperties.java`

```java
package com.skillhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "skillhub.search")
public class SearchProperties {
    private Semantic semantic = new Semantic();

    public Semantic getSemantic() { return semantic; }
    public void setSemantic(Semantic semantic) { this.semantic = semantic; }

    public static class Semantic {
        private boolean enabled = true;
        private double weight = 0.35;
        private int candidateMultiplier = 8;
        private int maxCandidates = 120;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }
        public int getCandidateMultiplier() { return candidateMultiplier; }
        public void setCandidateMultiplier(int candidateMultiplier) { this.candidateMultiplier = candidateMultiplier; }
        public int getMaxCandidates() { return maxCandidates; }
        public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
    }
}
```

---

### 步骤 5：更新配置文件

**文件：** `server/skillhub-app/src/main/resources/application-local.yml`

添加：

```yaml
skillhub:
  search:
    semantic:
      enabled: true
      weight: 0.35
      candidate-multiplier: 8
      max-candidates: 120
```

---

### 步骤 6：创建 Skill 生命周期监听器（自动向量化）

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/listener/SkillIndexingListener.java`

```java
package com.skillhub.domain.skill.listener;

import com.skillhub.domain.skill.entity.Skill;
import com.skillhub.enterprise.search.service.VectorSearchService;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class SkillIndexingListener {

    private static final Logger log = LoggerFactory.getLogger(SkillIndexingListener.class);
    private static VectorSearchService vectorSearchService;

    @Autowired
    public void setVectorSearchService(@Lazy VectorSearchService vectorSearchService) {
        SkillIndexingListener.vectorSearchService = vectorSearchService;
    }

    @PostPersist
    @PostUpdate
    public void onSkillChange(Skill skill) {
        if (vectorSearchService == null) return;
        try {
            if ("PUBLISHED".equals(skill.getState())) {
                String textToIndex = skill.getName() + " " + (skill.getDescription() != null ? skill.getDescription() : "");
                vectorSearchService.indexSkill(skill.getId(), textToIndex);
                log.info("Auto-indexed skill {} after {}", skill.getId(), skill.getState());
            }
        } catch (Exception e) {
            log.error("Failed to auto-index skill {}", skill.getId(), e);
        }
    }
}
```

---

### 步骤 7：创建单元测试

**文件：** `server/skillhub-domain/src/test/java/com/skillhub/enterprise/search/service/VectorSearchServiceTest.java`

```java
package com.skillhub.enterprise.search.service;

import com.skillhub.domain.skill.entity.Skill;
import com.skillhub.domain.skill.repository.SkillRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private EmbeddingService embeddingService;
    @Mock private SkillRepository skillRepository;
    @Mock private Query query;
    private VectorSearchService vectorSearchService;

    @BeforeEach
    void setUp() {
        vectorSearchService = new VectorSearchService(entityManager, embeddingService, skillRepository);
    }

    @Test
    void semanticSearch_shouldReturnResults() {
        when(embeddingService.embed(anyString())).thenReturn(new float[1536]);
        when(entityManager.createNativeQuery(anyString(), eq(Skill.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);

        Page<Skill> result = vectorSearchService.semanticSearch("Java Spring", 0, 20);

        assertNotNull(result);
        verify(entityManager).createNativeQuery(contains("embedding <=>"), eq(Skill.class));
    }

    @Test
    void hasEmbedding_shouldReturnTrue_whenEmbeddingExists() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);

        assertTrue(vectorSearchService.hasEmbedding(1L));
    }

    @Test
    void hasEmbedding_shouldReturnFalse_whenNoEmbedding() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);

        assertFalse(vectorSearchService.hasEmbedding(1L));
    }

    @Test
    void indexSkill_shouldUpdateEmbedding() {
        when(embeddingService.embed(anyString())).thenReturn(new float[1536]);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        vectorSearchService.indexSkill(1L, "Java Spring Boot");

        verify(entityManager).createNativeQuery(contains("UPDATE skills SET embedding"));
    }
}
```

---

## 四、前端实现

### 4.1 语义搜索框组件

**文件：** `web/src/features/skill/components/SemanticSearchBox.tsx`

```tsx
import { Input, Card, List, Tag, Space, Spin, Empty } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useRequest } from '@tanstack/react-query';
import { skillSearchApi } from '@/services/api';
import type { SkillVO } from '@/types';

const { Search } = Input;

interface SemanticSearchBoxProps {
  onSelectSkill?: (skill: SkillVO) => void;
  placeholder?: string;
}

export const SemanticSearchBox: React.FC<SemanticSearchBoxProps> = ({
  onSelectSkill,
  placeholder = "搜索技能...",
}) => {
  const [query, setQuery] = useState('');
  const [searchType, setSearchType] = useState<'semantic' | 'hybrid'>('semantic');

  const { data: results, isLoading, run: search } = useRequest(
    (q: string, type: string) =>
      type === 'semantic'
        ? skillSearchApi.semanticSearch(q)
        : skillSearchApi.hybridSearch(q),
    { manual: true }
  );

  const handleSearch = (value: string) => {
    if (value.trim()) {
      setQuery(value);
      search(value, searchType);
    }
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Card>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space>
            <Search
              placeholder={placeholder}
              allowClear
              enterButton={<SearchOutlined />}
              size="large"
              onSearch={handleSearch}
              style={{ flex: 1 }}
            />
            <Space>
              <Tag
                color={searchType === 'semantic' ? 'blue' : 'default'}
                onClick={() => setSearchType('semantic')}
                style={{ cursor: 'pointer' }}
              >语义搜索</Tag>
              <Tag
                color={searchType === 'hybrid' ? 'blue' : 'default'}
                onClick={() => setSearchType('hybrid')}
                style={{ cursor: 'pointer' }}
              >混合搜索</Tag>
            </Space>
          </Space>
        </Space>
      </Card>

      {isLoading && (
        <Card><Space style={{ width: '100%', justifyContent: 'center' }}><Spin /> 搜索中...</Space></Card>
      )}

      {query && results && results.content?.length === 0 && (
        <Card><Empty description={`未找到与"${query}"相关的技能`} /></Card>
      )}

      {results && results.content?.length > 0 && (
        <Card title={`找到 ${results.totalElements} 个相关技能`}>
          <List
            dataSource={results.content}
            renderItem={(skill: SkillVO) => (
              <List.Item onClick={() => onSelectSkill?.(skill)} style={{ cursor: 'pointer' }}>
                <List.Item.Meta
                  title={
                    <Space>
                      <span>{skill.name}</span>
                      {skill.dimensions?.map((dim: any) => (
                        <Tag key={dim.dimensionId} color={dim.dimensionCode}>{dim.value || dim.dimensionName}</Tag>
                      ))}
                    </Space>
                  }
                  description={skill.description}
                />
              </List.Item>
            )}
          />
        </Card>
      )}
    </Space>
  );
};
```

### 4.2 API 客户端

**文件：** `web/src/services/api/skillSearch.ts`

```typescript
import { client } from '../client';
import type { SkillVO, Page } from '../types';

export const skillSearchApi = {
  semanticSearch: (query: string, page = 0, size = 20) =>
    client.get<Page<SkillVO>>('/api/v1/skills/search/semantic', { params: { query, page, size } }),

  hybridSearch: (query: string, vectorWeight = 0.35, page = 0, size = 20) =>
    client.get<Page<SkillVO>>('/api/v1/skills/search/hybrid', { params: { query, vector_weight: vectorWeight, page, size } }),

  reindexSkill: (skillId: number) =>
    client.post(`/api/v1/skills/search/${skillId}/reindex`),

  reindexAll: () =>
    client.post<{ indexedCount: number }>('/api/v1/skills/search/reindex-all'),

  getSimilarity: (skillId: number, query: string) =>
    client.get<number>(`/api/v1/skills/search/${skillId}/similarity`, { params: { query } }),

  hasEmbedding: (skillId: number) =>
    client.get<boolean>(`/api/v1/skills/search/${skillId}/has-embedding`),
};
```

---

## 五、API 接口汇总

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/api/v1/skills/search/semantic` | 语义搜索 | 需要 |
| GET | `/api/v1/skills/search/hybrid` | 混合搜索 | 需要 |
| POST | `/api/v1/skills/search/{skillId}/reindex` | 重新索引单个技能 | 管理员 |
| POST | `/api/v1/skills/search/reindex-all` | 批量重新索引 | 管理员 |
| GET | `/api/v1/skills/search/{skillId}/similarity` | 获取相似度 | 需要 |
| GET | `/api/v1/skills/search/{skillId}/has-embedding` | 检查是否有向量 | 需要 |

---

## 六、验证测试

```bash
# 语义搜索
curl "http://localhost:8080/api/v1/skills/search/semantic?query=Java%20Spring%20Boot"

# 混合搜索
curl "http://localhost:8080/api/v1/skills/search/hybrid?query=Java&vector_weight=0.5"

# 批量重新索引
curl -X POST http://localhost:8080/api/v1/skills/search/reindex-all
```

---

## 七、后续步骤

Sprint 3 完成后，进入 **Sprint 4：主动提交 RAG**：

| 任务 | 交付物 |
|------|--------|
| 知识库数据库设计 | knowledge_libraries, knowledge_chunks 表 |
| 文档解析服务 | DocumentParserService |
| 知识库 API | KnowledgeLibrary API |
| 文档上传组件 | DocumentUpload.tsx |
| RAG 检索 API | `/api/v1/rag/*` |

---

## 八、代码位置索引

| 文件 | 路径 |
|------|------|
| EmbeddingService | `server/skillhub-domain/src/main/java/com/skillhub/enterprise/search/service/EmbeddingService.java` |
| VectorSearchService | `server/skillhub-domain/src/main/java/com/skillhub/enterprise/search/service/VectorSearchService.java` |
| Controller | `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/SkillSearchController.java` |
| SearchProperties | `server/skillhub-app/src/main/java/com/skillhub/config/SearchProperties.java` |
| SkillIndexingListener | `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/listener/SkillIndexingListener.java` |
| 配置更新 | `server/skillhub-app/src/main/resources/application-local.yml` |
| 前端 SemanticSearchBox | `web/src/features/skill/components/SemanticSearchBox.tsx` |
| 前端 API | `web/src/services/api/skillSearch.ts` |
| 单元测试 | `server/skillhub-domain/src/test/java/com/skillhub/enterprise/search/service/VectorSearchServiceTest.java` |

---

**文档结束**
