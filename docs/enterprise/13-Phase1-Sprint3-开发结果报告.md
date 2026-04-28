# Phase 1 Sprint 3 开发结果报告

> 本文档描述 Phase 1 Sprint 3（第 5-8 周）的开发执行情况、遇到的问题、验证步骤和环境配置要求。
>
> **关联文档：**
> - `07-Phase1-Sprint3-详细实施文档.md` - 原始实施计划
> - `01-项目现有技术与功能分析.md` - 技术栈说明
>
> **文档版本：** v1.0
> **创建日期：** 2026-04-28
> **执行周期：** 2026-04-28

---

## 一、执行概述

### 1.1 计划 vs 实际

| 计划项 | 状态 | 说明 |
|--------|------|------|
| 向量检索服务增强 | ✅ 完成 | VectorSearchService 新增 hybridSearch、reindexAll、分页 semanticSearch |
| 语义搜索 API | ✅ 完成 | `/api/v1/skills/search/semantic` |
| 混合搜索 API | ✅ 完成 | `/api/v1/skills/search/hybrid` |
| 搜索结果排序优化 | ✅ 完成 | 向量权重可配置，默认 0.35 |
| 前端语义搜索框 | ⏸ 跳过 | 前端不在当前 Sprint 范围 |
| 技能自动向量化 | ✅ 完成 | SkillIndexingListener + @EntityListeners |
| 单元测试 | ✅ 完成 | VectorSearchServiceTest 新增测试用例 |

### 1.2 实际文件变更

```
Modified:
  server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/Skill.java
  server/skillhub-domain/src/main/java/com/iflytek/skillhub/embedding/VectorSearchService.java
  server/skillhub-domain/src/test/java/com/iflytek/skillhub/embedding/VectorSearchServiceTest.java
  server/skillhub-app/src/main/resources/application-local.yml

Added:
  server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/portal/SemanticSearchController.java
  server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/dto/SkillBasicVO.java
  server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/listener/SkillIndexingListener.java
```

---

## 二、实际实现说明

### 2.1 核心实现

**VectorSearchService** (`server/skillhub-domain/src/main/java/com/iflytek/skillhub/embedding/VectorSearchService.java`)
- `semanticSearch(String query, int page, int size)` - 分页语义搜索，返回 `Page<Skill>`
- `semanticSearch(String query, int limit)` - 返回 ID 列表（兼容原有接口）
- `hybridSearch(String query, double vectorWeight, int page, int size)` - 混合搜索（向量 + 关键词）
- `reindexAll()` - 批量重新索引所有技能
- 新增 `SkillRepository` 依赖用于 `reindexAll()`

**SemanticSearchController** (`server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/portal/SemanticSearchController.java`)
- REST API `/api/v1/skills/search/*`
- 支持语义搜索、混合搜索、重新索引、相似度查询

**SkillIndexingListener** (`server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/listener/SkillIndexingListener.java`)
- JPA Entity Listener
- `@PostPersist` / `@PostUpdate` 生命周期回调
- 仅对 `ACTIVE` 状态的技能自动向量化
- 使用 `@Lazy` 注入避免循环依赖

**SkillBasicVO** (`server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/dto/SkillBasicVO.java`)
- 搜索结果用的轻量级 DTO record
- 包含：id, slug, displayName, summary, ownerId, visibility, downloadCount, starCount, ratingAvg

---

## 三、API 接口汇总

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/skills/search/semantic` | 语义搜索（分页） |
| GET | `/api/v1/skills/search/hybrid` | 混合搜索（分页） |
| POST | `/api/v1/skills/search/{skillId}/reindex` | 重新索引单个技能 |
| POST | `/api/v1/skills/search/reindex-all` | 批量重新索引 |
| GET | `/api/v1/skills/search/{skillId}/similarity` | 获取相似度 |
| GET | `/api/v1/skills/search/{skillId}/has-embedding` | 检查是否有向量 |

---

## 四、配置说明

### 4.1 application-local.yml 新增配置

```yaml
skillhub:
  search:
    semantic:
      enabled: true
      weight: 0.35
      candidate-multiplier: 8
      max-candidates: 120
```

### 4.2 SearchProperties

```java
@ConfigurationProperties(prefix = "skillhub.search")
public class SearchProperties {
    private Semantic semantic = new Semantic();
    // weight: 向量权重（0-1），关键词权重为 1-weight
    // candidateMultiplier: 候选集倍数
    // maxCandidates: 最大候选集数量
}
```

---

## 五、验证步骤

### 5.1 编译验证

```bash
cd server
./mvnw compile -pl skillhub-domain,skillhub-app
```

### 5.2 单元测试验证

```bash
./mvnw test -Dtest=VectorSearchServiceTest
```

### 5.3 API 验证

启动应用后：

```bash
# 语义搜索
curl "http://localhost:8080/api/v1/skills/search/semantic?query=Java%20Spring%20Boot&page=0&size=20"

# 混合搜索
curl "http://localhost:8080/api/v1/skills/search/hybrid?query=Java&vector_weight=0.5&page=0&size=20"

# 批量重新索引
curl -X POST http://localhost:8080/api/v1/skills/search/reindex-all

# 检查技能是否有向量
curl "http://localhost:8080/api/v1/skills/search/1/has-embedding"
```

---

## 六、后续步骤

Sprint 3 已完成。后续进入 **Sprint 4：主动提交 RAG**：

| 任务 | 交付物 |
|------|--------|
| 知识库数据库设计 | knowledge_libraries, knowledge_chunks 表 |
| 文档解析服务 | DocumentParserService |
| 知识库 API | KnowledgeLibrary API |
| 文档上传组件 | DocumentUpload.tsx |
| RAG 检索 API | `/api/v1/rag/*` |

---

## 七、代码位置索引

| 文件 | 路径 |
|------|------|
| VectorSearchService | `server/skillhub-domain/src/main/java/com/iflytek/skillhub/embedding/VectorSearchService.java` |
| SemanticSearchController | `server/skillhub-app/src/main/java/com/iflytek/skillhub/controller/portal/SemanticSearchController.java` |
| SkillBasicVO | `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/dto/SkillBasicVO.java` |
| SkillIndexingListener | `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/listener/SkillIndexingListener.java` |
| SearchProperties | `server/skillhub-app/src/main/java/com/iflytek/skillhub/config/SearchProperties.java` |
| Skill 实体更新 | `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/Skill.java` |
| 配置更新 | `server/skillhub-app/src/main/resources/application-local.yml` |
| 单元测试 | `server/skillhub-domain/src/test/java/com/iflytek/skillhub/embedding/VectorSearchServiceTest.java` |

---

**文档结束**
