# Phase 1 Sprint 2 详细实施文档

> 本文档是 Phase 1 Sprint 2（第 3-4 周）的详细实施指南，包含多维度分类体系的完整实现。
>
> **关联文档：**
> - `01-项目现有技术与功能分析.md` - 现有技术栈
> - `04-实际实施开发计划.md` - Phase 1 整体计划
> - `05-Phase1-Sprint1-详细实施文档.md` - Sprint 1 向量基础设施
>
> **文档版本：** v1.0
> **创建日期：** 2026-04-22
> **Sprint 周期：** 第 3-4 周

---

## 一、Sprint 2 目标

**交付物：**
1. `skill_dimensions` 维度定义表
2. `skill_dimensions_mapping` 技能维度映射表
3. 维度管理 API（CRUD）
4. 技能-维度绑定 API
5. 前端维度树组件
6. 技能列表多维度筛选

**验收标准：**
- 维度树展示正常（支持人员/项目/职能/层级四种维度）
- 技能可绑定多个维度的标签
- 支持按维度筛选技能列表
- 单元测试覆盖率达到 80%+

---

## 二、技术准备

### 2.1 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21 | LTS 版本 |
| Spring Boot | 3.2.3 | 当前项目版本 |
| PostgreSQL | 16 | 带 pgvector 扩展（已在 Sprint 1 集成） |

### 2.2 数据库变更

**文件：** `server/skillhub-app/src/main/resources/db/migration/V2__add_skill_dimensions.sql`

```sql
-- 维度定义表
CREATE TABLE skill_dimensions (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(50) NOT NULL UNIQUE,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    icon            VARCHAR(50),
    color           VARCHAR(20),
    parent_id       BIGINT REFERENCES skill_dimensions(id),
    sort_order      INTEGER DEFAULT 0,
    status          VARCHAR(20) DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 技能-维度映射表
CREATE TABLE skill_dimensions_mapping (
    id              BIGSERIAL PRIMARY KEY,
    skill_id        BIGINT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    dimension_id    BIGINT NOT NULL REFERENCES skill_dimensions(id) ON DELETE CASCADE,
    value           VARCHAR(255),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(skill_id, dimension_id, value)
);

-- 创建索引
CREATE INDEX idx_sdm_skill ON skill_dimensions_mapping(skill_id);
CREATE INDEX idx_sdm_dimension ON skill_dimensions_mapping(dimension_id);
CREATE INDEX idx_sdm_value ON skill_dimensions_mapping(value);
CREATE INDEX idx_sd_parent ON skill_dimensions(parent_id);
CREATE INDEX idx_sd_status ON skill_dimensions(status);
```

**初始化数据：**

```sql
-- 人员维度
INSERT INTO skill_dimensions (code, name, description, icon, color, parent_id, sort_order) VALUES
('employee', '人员', '按人员维度分类', 'UserOutlined', '#1890ff', NULL, 1);

-- 项目维度
INSERT INTO skill_dimensions (code, name, description, icon, color, parent_id, sort_order) VALUES
('project', '项目', '按项目维度分类', 'FolderOutlined', '#52c41a', NULL, 2);

-- 职能维度
INSERT INTO skill_dimensions (code, name, description, icon, color, parent_id, sort_order) VALUES
('function', '职能', '按职能维度分类', 'ClusterOutlined', '#fa8c16', NULL, 3);

-- 层级维度
INSERT INTO skill_dimensions (code, name, description, icon, color, parent_id, sort_order) VALUES
('level', '层级', '按技能层级分类', 'RiseOutlined', '#f5222d', NULL, 4);
```

**执行命令：**
```bash
cd D:/person_ai_projects/first_version/skillhub
docker-compose restart skillhub-app
# 或者本地开发模式
make dev-server-restart
```

**验证：**
```bash
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "\d skill_dimensions"
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "\d skill_dimensions_mapping"
docker exec -it skillhub-postgres psql -U skillhub -d skillhub -c "SELECT * FROM skill_dimensions;"
```

---

## 三、详细实施步骤

### 步骤 1：创建实体类

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/entity/SkillDimension.java`

```java
package com.skillhub.domain.skill.dimension.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "skill_dimensions")
public class SkillDimension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    private String icon;

    @Column(length = 20)
    private String color;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private SkillDimension parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<SkillDimension> children = new ArrayList<>();

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(length = 20)
    private String status = "active";

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
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public SkillDimension getParent() { return parent; }
    public void setParent(SkillDimension parent) { this.parent = parent; }
    public List<SkillDimension> getChildren() { return children; }
    public void setChildren(List<SkillDimension> children) { this.children = children; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/entity/SkillDimensionMapping.java`

```java
package com.skillhub.domain.skill.dimension.entity;

import com.skillhub.domain.skill.entity.Skill;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "skill_dimensions_mapping",
       uniqueConstraints = @UniqueConstraint(columnNames = {"skill_id", "dimension_id", "value"}))
public class SkillDimensionMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dimension_id", nullable = false)
    private SkillDimension dimension;

    @Column(length = 255)
    private String value;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Skill getSkill() { return skill; }
    public void setSkill(Skill skill) { this.skill = skill; }
    public SkillDimension getDimension() { return dimension; }
    public void setDimension(SkillDimension dimension) { this.dimension = dimension; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

---

### 步骤 2：创建 Repository

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/repository/SkillDimensionRepository.java`

```java
package com.skillhub.domain.skill.dimension.repository;

import com.skillhub.domain.skill.dimension.entity.SkillDimension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SkillDimensionRepository extends JpaRepository<SkillDimension, Long> {

    @Query("SELECT d FROM SkillDimension d WHERE d.parent IS NULL AND d.status = :status ORDER BY d.sortOrder")
    List<SkillDimension> findRootDimensions(@Param("status") String status);

    @Query("SELECT d FROM SkillDimension d WHERE d.parent.id = :parentId AND d.status = :status ORDER BY d.sortOrder")
    List<SkillDimension> findByParentId(@Param("parentId") Long parentId, @Param("status") String status);

    Optional<SkillDimension> findByCode(String code);

    @Query("SELECT d FROM SkillDimension d WHERE d.status = 'active' ORDER BY d.sortOrder")
    List<SkillDimension> findAllActive();
}
```

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/repository/SkillDimensionMappingRepository.java`

```java
package com.skillhub.domain.skill.dimension.repository;

import com.skillhub.domain.skill.dimension.entity.SkillDimensionMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SkillDimensionMappingRepository extends JpaRepository<SkillDimensionMapping, Long> {

    List<SkillDimensionMapping> findBySkillId(Long skillId);
    List<SkillDimensionMapping> findByDimensionId(Long dimensionId);
    void deleteBySkillId(Long skillId);
    void deleteBySkillIdAndDimensionId(Long skillId, Long dimensionId);
    boolean existsBySkillIdAndDimensionIdAndValue(Long skillId, Long dimensionId, String value);

    @Query("SELECT m FROM SkillDimensionMapping m WHERE m.dimension.id = :dimensionId AND m.value = :value")
    Page<SkillDimensionMapping> findByDimensionIdAndValue(
        @Param("dimensionId") Long dimensionId,
        @Param("value") String value,
        Pageable pageable
    );

    Optional<SkillDimensionMapping> findBySkillIdAndDimensionId(Long skillId, Long dimensionId);
}
```

---

### 步骤 3：创建 DTO

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/dto/DimensionTreeVO.java`

```java
package com.skillhub.domain.skill.dimension.dto;

import java.util.List;

public class DimensionTreeVO {
    private Long id;
    private String code;
    private String name;
    private String description;
    private String icon;
    private String color;
    private List<DimensionTreeVO> children;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public List<DimensionTreeVO> getChildren() { return children; }
    public void setChildren(List<DimensionTreeVO> children) { this.children = children; }
}
```

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/dto/DimensionMappingRequest.java`

```java
package com.skillhub.domain.skill.dimension.dto;

import jakarta.validation.constraints.NotNull;

public class DimensionMappingRequest {
    @NotNull(message = "维度ID不能为空")
    private Long dimensionId;
    private String value;

    public Long getDimensionId() { return dimensionId; }
    public void setDimensionId(Long dimensionId) { this.dimensionId = dimensionId; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
```

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/dto/SkillDimensionVO.java`

```java
package com.skillhub.domain.skill.dimension.dto;

import java.time.LocalDateTime;

public class SkillDimensionVO {
    private Long id;
    private Long skillId;
    private String skillName;
    private Long dimensionId;
    private String dimensionName;
    private String dimensionCode;
    private String value;
    private LocalDateTime createdAt;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public Long getDimensionId() { return dimensionId; }
    public void setDimensionId(Long dimensionId) { this.dimensionId = dimensionId; }
    public String getDimensionName() { return dimensionName; }
    public void setDimensionName(String dimensionName) { this.dimensionName = dimensionName; }
    public String getDimensionCode() { return dimensionCode; }
    public void setDimensionCode(String dimensionCode) { this.dimensionCode = dimensionCode; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

---

### 步骤 4：创建 Service

**文件：** `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/service/SkillDimensionService.java`

```java
package com.skillhub.domain.skill.dimension.service;

import com.skillhub.domain.skill.dimension.dto.*;
import com.skillhub.domain.skill.dimension.entity.SkillDimension;
import com.skillhub.domain.skill.dimension.entity.SkillDimensionMapping;
import com.skillhub.domain.skill.dimension.repository.SkillDimensionMappingRepository;
import com.skillhub.domain.skill.dimension.repository.SkillDimensionRepository;
import com.skillhub.domain.skill.entity.Skill;
import com.skillhub.domain.skill.repository.SkillRepository;
import com.skillhub.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class SkillDimensionService {

    private final SkillDimensionRepository dimensionRepository;
    private final SkillDimensionMappingRepository mappingRepository;
    private final SkillRepository skillRepository;

    public SkillDimensionService(
            SkillDimensionRepository dimensionRepository,
            SkillDimensionMappingRepository mappingRepository,
            SkillRepository skillRepository) {
        this.dimensionRepository = dimensionRepository;
        this.mappingRepository = mappingRepository;
        this.skillRepository = skillRepository;
    }

    @Transactional(readOnly = true)
    public List<DimensionTreeVO> getDimensionTree() {
        List<SkillDimension> rootDimensions = dimensionRepository.findRootDimensions("active");
        return rootDimensions.stream().map(this::buildTree).collect(Collectors.toList());
    }

    private DimensionTreeVO buildTree(SkillDimension dimension) {
        DimensionTreeVO vo = new DimensionTreeVO();
        vo.setId(dimension.getId());
        vo.setCode(dimension.getCode());
        vo.setName(dimension.getName());
        vo.setDescription(dimension.getDescription());
        vo.setIcon(dimension.getIcon());
        vo.setColor(dimension.getColor());
        List<SkillDimension> children = dimensionRepository.findByParentId(dimension.getId(), "active");
        if (!children.isEmpty()) {
            vo.setChildren(children.stream().map(this::buildTree).collect(Collectors.toList()));
        }
        return vo;
    }

    public void mapSkillToDimension(Long skillId, DimensionMappingRequest request) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new ResourceNotFoundException("Skill not found: " + skillId));
        SkillDimension dimension = dimensionRepository.findById(request.getDimensionId())
                .orElseThrow(() -> new ResourceNotFoundException("Dimension not found: " + request.getDimensionId()));
        if (mappingRepository.existsBySkillIdAndDimensionIdAndValue(skillId, dimension.getId(), request.getValue())) {
            return;
        }
        SkillDimensionMapping mapping = new SkillDimensionMapping();
        mapping.setSkill(skill);
        mapping.setDimension(dimension);
        mapping.setValue(request.getValue());
        mappingRepository.save(mapping);
    }

    public void removeSkillDimensionMapping(Long skillId, Long dimensionId) {
        mappingRepository.deleteBySkillIdAndDimensionId(skillId, dimensionId);
    }

    @Transactional(readOnly = true)
    public List<SkillDimensionVO> getSkillDimensions(Long skillId) {
        return mappingRepository.findBySkillId(skillId).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<Skill> getSkillsByDimension(Long dimensionId, String value, Pageable pageable) {
        return mappingRepository.findByDimensionIdAndValue(dimensionId, value, pageable)
                .map(SkillDimensionMapping::getSkill);
    }

    @Transactional(readOnly = true)
    public SkillDimension getDimensionById(Long dimensionId) {
        return dimensionRepository.findById(dimensionId)
                .orElseThrow(() -> new ResourceNotFoundException("Dimension not found: " + dimensionId));
    }

    @Transactional(readOnly = true)
    public List<String> getDimensionValues(Long dimensionId) {
        return mappingRepository.findByDimensionId(dimensionId).stream()
                .map(SkillDimensionMapping::getValue)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private SkillDimensionVO toVO(SkillDimensionMapping mapping) {
        SkillDimensionVO vo = new SkillDimensionVO();
        vo.setId(mapping.getId());
        vo.setSkillId(mapping.getSkill().getId());
        vo.setSkillName(mapping.getSkill().getName());
        vo.setDimensionId(mapping.getDimension().getId());
        vo.setDimensionName(mapping.getDimension().getName());
        vo.setDimensionCode(mapping.getDimension().getCode());
        vo.setValue(mapping.getValue());
        vo.setCreatedAt(mapping.getCreatedAt());
        return vo;
    }
}
```

---

### 步骤 5：创建 Controller

**文件：** `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/SkillDimensionController.java`

```java
package com.skillhub.controller.enterprise;

import com.skillhub.common.api.Result;
import com.skillhub.domain.skill.dimension.dto.*;
import com.skillhub.domain.skill.dimension.entity.SkillDimension;
import com.skillhub.domain.skill.dimension.service.SkillDimensionService;
import com.skillhub.domain.skill.dto.SkillVO;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dimensions")
public class SkillDimensionController {

    private final SkillDimensionService dimensionService;

    public SkillDimensionController(SkillDimensionService dimensionService) {
        this.dimensionService = dimensionService;
    }

    @GetMapping("/tree")
    public Result<List<DimensionTreeVO>> getDimensionTree() {
        return Result.success(dimensionService.getDimensionTree());
    }

    @GetMapping("/{dimensionId}")
    public Result<SkillDimension> getDimension(@PathVariable Long dimensionId) {
        return Result.success(dimensionService.getDimensionById(dimensionId));
    }

    @GetMapping("/{dimensionId}/values")
    public Result<List<String>> getDimensionValues(@PathVariable Long dimensionId) {
        return Result.success(dimensionService.getDimensionValues(dimensionId));
    }

    @PostMapping("/skills/{skillId}")
    public Result<Void> mapSkillToDimension(
            @PathVariable Long skillId,
            @Valid @RequestBody DimensionMappingRequest request) {
        dimensionService.mapSkillToDimension(skillId, request);
        return Result.success();
    }

    @PostMapping("/skills/{skillId}/batch")
    public Result<Void> batchMapSkillToDimension(
            @PathVariable Long skillId,
            @Valid @RequestBody List<DimensionMappingRequest> requests) {
        for (DimensionMappingRequest request : requests) {
            dimensionService.mapSkillToDimension(skillId, request);
        }
        return Result.success();
    }

    @DeleteMapping("/skills/{skillId}/{dimensionId}")
    public Result<Void> removeSkillDimension(
            @PathVariable Long skillId,
            @PathVariable Long dimensionId) {
        dimensionService.removeSkillDimensionMapping(skillId, dimensionId);
        return Result.success();
    }

    @GetMapping("/skills/{skillId}")
    public Result<List<SkillDimensionVO>> getSkillDimensions(@PathVariable Long skillId) {
        return Result.success(dimensionService.getSkillDimensions(skillId));
    }

    @GetMapping("/{dimensionId}/skills")
    public Result<Page<SkillVO>> getSkillsByDimension(
            @PathVariable Long dimensionId,
            @RequestParam(required = false) String value,
            @PageableDefault(size = 20) Pageable pageable) {
        return Result.success(dimensionService.getSkillsByDimension(dimensionId, value, pageable)
                .map(SkillVO::fromEntity));
    }
}
```

---

### 步骤 6：创建单元测试

**文件：** `server/skillhub-domain/src/test/java/com/skillhub/domain/skill/dimension/service/SkillDimensionServiceTest.java`

```java
package com.skillhub.domain.skill.dimension.service;

import com.skillhub.domain.skill.dimension.dto.DimensionMappingRequest;
import com.skillhub.domain.skill.dimension.dto.DimensionTreeVO;
import com.skillhub.domain.skill.dimension.entity.SkillDimension;
import com.skillhub.domain.skill.dimension.entity.SkillDimensionMapping;
import com.skillhub.domain.skill.dimension.repository.SkillDimensionMappingRepository;
import com.skillhub.domain.skill.dimension.repository.SkillDimensionRepository;
import com.skillhub.domain.skill.entity.Skill;
import com.skillhub.domain.skill.repository.SkillRepository;
import com.skillhub.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillDimensionServiceTest {

    @Mock
    private SkillDimensionRepository dimensionRepository;
    @Mock
    private SkillDimensionMappingRepository mappingRepository;
    @Mock
    private SkillRepository skillRepository;

    @InjectMocks
    private SkillDimensionService dimensionService;

    private SkillDimension testDimension;
    private Skill testSkill;

    @BeforeEach
    void setUp() {
        testDimension = new SkillDimension();
        testDimension.setId(1L);
        testDimension.setCode("employee");
        testDimension.setName("人员");
        testDimension.setStatus("active");
        testDimension.setSortOrder(1);

        testSkill = new Skill();
        testSkill.setId(1L);
        testSkill.setName("Java Development");
    }

    @Test
    void getDimensionTree_shouldReturnTreeStructure() {
        when(dimensionRepository.findRootDimensions("active")).thenReturn(List.of(testDimension));
        when(dimensionRepository.findByParentId(1L, "active")).thenReturn(Collections.emptyList());

        List<DimensionTreeVO> result = dimensionService.getDimensionTree();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("employee", result.get(0).getCode());
        assertEquals("人员", result.get(0).getName());
    }

    @Test
    void mapSkillToDimension_shouldCreateMapping() {
        DimensionMappingRequest request = new DimensionMappingRequest();
        request.setDimensionId(1L);
        request.setValue("张三");

        when(skillRepository.findById(1L)).thenReturn(Optional.of(testSkill));
        when(dimensionRepository.findById(1L)).thenReturn(Optional.of(testDimension));
        when(mappingRepository.existsBySkillIdAndDimensionIdAndValue(1L, 1L, "张三")).thenReturn(false);
        when(mappingRepository.save(any(SkillDimensionMapping.class))).thenAnswer(i -> i.getArgument(0));

        dimensionService.mapSkillToDimension(1L, request);

        verify(mappingRepository).save(any(SkillDimensionMapping.class));
    }

    @Test
    void mapSkillToDimension_shouldSkipIfExists() {
        DimensionMappingRequest request = new DimensionMappingRequest();
        request.setDimensionId(1L);
        request.setValue("张三");

        when(skillRepository.findById(1L)).thenReturn(Optional.of(testSkill));
        when(dimensionRepository.findById(1L)).thenReturn(Optional.of(testDimension));
        when(mappingRepository.existsBySkillIdAndDimensionIdAndValue(1L, 1L, "张三")).thenReturn(true);

        dimensionService.mapSkillToDimension(1L, request);

        verify(mappingRepository, never()).save(any());
    }

    @Test
    void mapSkillToDimension_shouldThrowWhenSkillNotFound() {
        DimensionMappingRequest request = new DimensionMappingRequest();
        request.setDimensionId(1L);
        when(skillRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                dimensionService.mapSkillToDimension(999L, request));
    }

    @Test
    void removeSkillDimensionMapping_shouldDeleteMapping() {
        dimensionService.removeSkillDimensionMapping(1L, 1L);
        verify(mappingRepository).deleteBySkillIdAndDimensionId(1L, 1L);
    }

    @Test
    void getSkillDimensions_shouldReturnDimensionList() {
        SkillDimensionMapping mapping = new SkillDimensionMapping();
        mapping.setId(1L);
        mapping.setSkill(testSkill);
        mapping.setDimension(testDimension);
        mapping.setValue("张三");

        when(mappingRepository.findBySkillId(1L)).thenReturn(List.of(mapping));

        var result = dimensionService.getSkillDimensions(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("张三", result.get(0).getValue());
    }
}
```

---

## 四、前端实现

### 4.1 维度树组件

**文件：** `web/src/features/skill/components/DimensionTree.tsx`

```tsx
import { Tree, Card, Spin } from 'antd';
import { useRequest } from '@tanstack/react-query';
import { dimensionApi } from '@/services/api';
import type { DataNode } from 'antd/es/tree';

interface DimensionTreeProps {
  onSelect: (dimensionId: number, dimensionCode: string) => void;
}

export const DimensionTree: React.FC<DimensionTreeProps> = ({ onSelect }) => {
  const { data, isLoading } = useRequest({
    queryKey: ['dimensions', 'tree'],
    queryFn: () => dimensionApi.getDimensionTree(),
  });

  const buildTreeData = (dimensions: any[]): DataNode[] => {
    return dimensions.map((dim) => ({
      key: dim.id,
      title: <span style={{ color: dim.color }}>{dim.name}</span>,
      code: dim.code,
      children: dim.children?.length > 0 ? buildTreeData(dim.children) : undefined,
    }));
  };

  if (isLoading) {
    return <Card><Spin /></Card>;
  }

  return (
    <Card title="技能维度" bordered={false}>
      <Tree
        treeData={data || []}
        onSelect={(keys, info) => {
          if (keys.length > 0) {
            const dimension = info.node as any;
            onSelect(dimension.key as number, dimension.code);
          }
        }}
        showIcon
        defaultExpandAll
      />
    </Card>
  );
};
```

### 4.2 技能列表（带维度筛选）

**文件：** `web/src/features/skill/components/SkillListWithDimension.tsx`

```tsx
import { List, Card, Tag, Space, Select, Input, Spin } from 'antd';
import { useState } from 'react';
import { useRequest } from '@tanstack/react-query';
import { skillApi, dimensionApi } from '@/services/api';
import { DimensionTree } from './DimensionTree';

const { Search } = Input;

export const SkillListWithDimension: React.FC = () => {
  const [selectedDimensionId, setSelectedDimensionId] = useState<number | null>(null);
  const [selectedDimensionCode, setSelectedDimensionCode] = useState<string | null>(null);
  const [selectedValue, setSelectedValue] = useState<string | null>(null);
  const [searchKeyword, setSearchKeyword] = useState('');

  const { data: skills, isLoading: skillsLoading } = useRequest({
    queryKey: ['skills', 'dimension', selectedDimensionId, selectedValue, searchKeyword],
    queryFn: () => {
      if (selectedDimensionId) {
        return skillApi.getSkillsByDimension(selectedDimensionId, selectedValue || undefined);
      }
      return skillApi.searchSkills({ keyword: searchKeyword || undefined });
    },
  });

  const { data: dimensionValues } = useRequest({
    queryKey: ['dimensions', selectedDimensionId, 'values'],
    queryFn: () => dimensionApi.getDimensionValues(selectedDimensionId!),
    enabled: !!selectedDimensionId,
  });

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card>
        <Space>
          <Search
            placeholder="搜索技能"
            onSearch={setSearchKeyword}
            style={{ width: 200 }}
          />
          <DimensionTree
            onSelect={(dimensionId, dimensionCode) => {
              setSelectedDimensionId(dimensionId);
              setSelectedDimensionCode(dimensionCode);
              setSelectedValue(null);
            }}
          />
          {selectedDimensionId && dimensionValues && dimensionValues.length > 0 && (
            <Select
              placeholder="选择具体值"
              style={{ width: 150 }}
              allowClear
              onChange={setSelectedValue}
              options={dimensionValues.map((v: string) => ({ label: v, value: v }))}
            />
          )}
        </Space>
      </Card>

      <Spin spinning={skillsLoading}>
        <List
          dataSource={skills?.content || []}
          renderItem={(skill: any) => (
            <List.Item>
              <Card hoverable style={{ width: '100%' }}>
                <Space direction="vertical">
                  <Space>
                    <span style={{ fontWeight: 'bold', fontSize: 16 }}>{skill.name}</span>
                    {skill.dimensions?.map((dim: any) => (
                      <Tag key={dim.dimensionId} color={dim.dimensionCode}>
                        {dim.value || dim.dimensionName}
                      </Tag>
                    ))}
                  </Space>
                  <span style={{ color: '#666' }}>{skill.description}</span>
                </Space>
              </Card>
            </List.Item>
          )}
        />
      </Spin>
    </Space>
  );
};
```

---

## 五、API 接口汇总

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/api/v1/dimensions/tree` | 获取维度树 | 需要 |
| GET | `/api/v1/dimensions/{dimensionId}` | 获取维度详情 | 需要 |
| GET | `/api/v1/dimensions/{dimensionId}/values` | 获取维度值列表 | 需要 |
| POST | `/api/v1/dimensions/skills/{skillId}` | 绑定维度到技能 | 需要 |
| POST | `/api/v1/dimensions/skills/{skillId}/batch` | 批量绑定维度 | 需要 |
| DELETE | `/api/v1/dimensions/skills/{skillId}/{dimensionId}` | 解绑维度 | 需要 |
| GET | `/api/v1/dimensions/skills/{skillId}` | 获取技能的所有维度 | 需要 |
| GET | `/api/v1/dimensions/{dimensionId}/skills` | 按维度筛选技能 | 需要 |

---

## 六、验证测试

### 6.1 API 验证

```bash
# 获取维度树
curl http://localhost:8080/api/v1/dimensions/tree

# 为技能绑定维度
curl -X POST http://localhost:8080/api/v1/dimensions/skills/1 \
  -H "Content-Type: application/json" \
  -d '{"dimensionId": 1, "value": "张三"}'

# 获取技能维度
curl http://localhost:8080/api/v1/dimensions/skills/1

# 按维度筛选技能
curl "http://localhost:8080/api/v1/dimensions/1/skills?value=张三"
```

### 6.2 单元测试

```bash
cd server
./mvnw test -Dtest=SkillDimensionServiceTest
```

---

## 七、后续步骤

Sprint 2 完成后，进入 **Sprint 3：语义搜索能力**：

| 任务 | 交付物 |
|------|--------|
| 向量检索服务增强 | EmbeddingService + VectorSearchService |
| 语义搜索 API | `/api/v1/skills/search/semantic` |
| 混合搜索 API | `/api/v1/skills/search/hybrid` |
| 前端语义搜索框 | SemanticSearchBox.tsx |

---

## 八、代码位置索引

| 文件 | 路径 |
|------|------|
| 迁移脚本 | `server/skillhub-app/src/main/resources/db/migration/V2__add_skill_dimensions.sql` |
| SkillDimension 实体 | `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/entity/SkillDimension.java` |
| SkillDimensionMapping 实体 | `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/entity/SkillDimensionMapping.java` |
| Repository | `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/repository/*.java` |
| Service | `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/service/SkillDimensionService.java` |
| Controller | `server/skillhub-app/src/main/java/com/skillhub/controller/enterprise/SkillDimensionController.java` |
| DTO | `server/skillhub-domain/src/main/java/com/skillhub/domain/skill/dimension/dto/*.java` |
| 前端组件 | `web/src/features/skill/components/DimensionTree.tsx` |
| 前端组件 | `web/src/features/skill/components/SkillListWithDimension.tsx` |
| 单元测试 | `server/skillhub-domain/src/test/java/com/skillhub/domain/skill/dimension/service/SkillDimensionServiceTest.java` |

---

**文档结束**
