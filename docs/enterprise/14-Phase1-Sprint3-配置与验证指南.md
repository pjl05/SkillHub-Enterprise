# Phase 1 Sprint 3 配置与验证指南

> 本文档描述 Sprint 3 开发完成后的环境配置和验证步骤。
>
> **关联文档：**
> - `13-Phase1-Sprint3-开发结果报告.md` - 开发结果报告
> - `07-Phase1-Sprint3-详细实施文档.md` - 原始实施计划
>
> **文档版本：** v1.0
> **创建日期：** 2026-04-28

---

## 一、环境配置

### 1.1 数据库要求

Sprint 3 需要 PostgreSQL 数据库带 pgvector 扩展（已在 Sprint 1 集成）：

| 要求 | 说明 |
|------|------|
| PostgreSQL | 16 版本 |
| pgvector | 已安装并启用扩展 |
| skill 表 | 已有 embedding 列（由 Sprint 1 创建的 V39 迁移） |

### 1.2 配置文件

`application-local.yml` 已添加 `skillhub.search.semantic.*` 配置：

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

## 二、验证步骤

### 2.1 数据库验证

**步骤 1：验证 pgvector 扩展**

```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "SELECT extname FROM pg_extension WHERE extname = 'vector';"
```

**预期输出：**
```
 extname
---------
 vector
(1 row)
```

---

**步骤 2：验证 skill 表有 embedding 列**

```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "\d skill" | grep embedding
```

**预期输出应包含：**
```
 embedding        | vector(1024)
```

---

### 2.2 应用验证

**步骤 3：验证编译**

```bash
cd server
./mvnw compile -pl skillhub-domain,skillhub-app
```

**预期：编译成功，无错误**

---

**步骤 4：运行单元测试**

```bash
./mvnw test -Dtest=VectorSearchServiceTest
```

**预期：所有测试用例通过**

---

### 2.3 API 验证

**步骤 5：启动应用并验证 API**

启动应用：
```bash
cd server
./mvnw spring-boot:run -pl skillhub-app
```

验证语义搜索 API：
```bash
curl "http://localhost:8080/api/v1/skills/search/semantic?query=Java%20Spring%20Boot&page=0&size=20"
```

**预期响应：**
```json
{
  "code": 0,
  "message": "response.success.read",
  "data": {
    "content": [...],
    "totalElements": 10,
    "totalPages": 1,
    "size": 20,
    "number": 0
  }
}
```

---

**步骤 6：验证混合搜索 API**

```bash
curl "http://localhost:8080/api/v1/skills/search/hybrid?query=Java&vector_weight=0.5&page=0&size=20"
```

---

**步骤 7：验证批量重新索引**

```bash
curl -X POST http://localhost:8080/api/v1/skills/search/reindex-all
```

**预期响应：**
```json
{
  "code": 0,
  "message": "response.success.update",
  "data": {
    "indexedCount": 100
  }
}
```

---

**步骤 8：验证相似度查询**

```bash
curl "http://localhost:8080/api/v1/skills/search/1/similarity?query=Java%20Spring"
```

---

**步骤 9：验证向量存在检查**

```bash
curl "http://localhost:8080/api/v1/skills/search/1/has-embedding"
```

---

## 三、API 测试示例

### 3.1 语义搜索

```bash
curl "http://localhost:8080/api/v1/skills/search/semantic?query=Java%20Spring%20Boot&page=0&size=20"
```

### 3.2 混合搜索（调整权重）

```bash
curl "http://localhost:8080/api/v1/skills/search/hybrid?query=Java&vector_weight=0.7&page=0&size=20"
```

### 3.3 重新索引单个技能

```bash
curl -X POST "http://localhost:8080/api/v1/skills/search/1/reindex?text=Java%20Spring%20Boot%20Development"
```

### 3.4 批量重新索引

```bash
curl -X POST http://localhost:8080/api/v1/skills/search/reindex-all
```

### 3.5 获取相似度

```bash
curl "http://localhost:8080/api/v1/skills/search/1/similarity?query=Java%20development"
```

### 3.6 检查是否有向量

```bash
curl "http://localhost:8080/api/v1/skills/search/1/has-embedding"
```

---

## 四、常见问题排查

### 问题 1：pgvector 扩展未安装

**症状：**
`ERROR: operator class "vector_cosine_ops" does not exist`

**解决：**
1. 检查 PostgreSQL 版本（需要 16+）
2. 确认 pgvector 扩展已安装
3. 执行 `CREATE EXTENSION IF NOT EXISTS vector;`

---

### 问题 2：embedding 列为空

**症状：**
搜索返回空结果

**解决：**
1. 调用 `/api/v1/skills/search/reindex-all` 批量重建索引
2. 或等待 `SkillIndexingListener` 自动索引新发布的技能

---

### 问题 3：语义搜索超时

**症状：**
API 响应慢或超时

**解决：**
1. 减少 `max-candidates` 配置
2. 减小 `size` 参数值
3. 检查 EmbeddingService 是否可用

---

## 五、快速验证脚本

将以下内容保存为 `verify-sprint3.sh`：

```bash
#!/bin/bash

echo "=== Sprint 3 验证脚本 ==="
echo ""

# 1. 验证 pgvector 扩展
echo "[1/5] 验证 pgvector 扩展..."
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "SELECT extname FROM pg_extension WHERE extname = 'vector';" | grep -q "vector" && echo "  ✅ pgvector 扩展已安装" || echo "  ❌ pgvector 扩展未安装"

# 2. 验证 skill 表 embedding 列
echo "[2/5] 验证 skill 表 embedding 列..."
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "\d skill" | grep -q "embedding.*vector" && echo "  ✅ embedding 列存在" || echo "  ❌ embedding 列不存在"

# 3. 验证编译
echo "[3/5] 验证编译..."
cd server
./mvnw compile -pl skillhub-domain,skillhub-app -q && echo "  ✅ 编译成功" || echo "  ❌ 编译失败"

# 4. 运行单元测试
echo "[4/5] 运行单元测试..."
./mvnw test -Dtest=VectorSearchServiceTest -q && echo "  ✅ 单元测试通过" || echo "  ❌ 单元测试失败"

# 5. 检查配置
echo "[5/5] 检查配置..."
grep -q "skillhub.search.semantic" server/skillhub-app/src/main/resources/application-local.yml && echo "  ✅ search 配置存在" || echo "  ❌ search 配置缺失"

echo ""
echo "=== 验证完成 ==="
```

执行验证：
```bash
chmod +x verify-sprint3.sh
./verify-sprint3.sh
```

---

**文档结束**
