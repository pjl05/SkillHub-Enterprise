package com.iflytek.skillhub.embedding;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
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

/**
 * 向量检索服务
 * 负责向量插入和相似度查询
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);
    private final EntityManager entityManager;
    private final EmbeddingService embeddingService;
    private final SkillRepository skillRepository;

    public VectorSearchService(EntityManager entityManager,
                                EmbeddingService embeddingService,
                                SkillRepository skillRepository) {
        this.entityManager = entityManager;
        this.embeddingService = embeddingService;
        this.skillRepository = skillRepository;
    }

    /**
     * 为技能生成并存储向量
     *
     * @param skillId 技能ID
     * @param text     要向量化的文本（通常是技能的 name + description）
     */
    @Transactional
    public void indexSkill(Long skillId, String text) {
        if (text == null || text.isBlank()) {
            log.warn("Cannot index skill {} with empty text", skillId);
            return;
        }
        float[] embedding = embeddingService.embed(text);
        entityManager.createNativeQuery(
            "UPDATE skill SET embedding = ? WHERE id = ?"
        ).setParameter(1, FloatArrayType.toPGvector(embedding))
         .setParameter(2, skillId)
         .executeUpdate();
        log.info("Indexed skill {} with embedding vector", skillId);
    }

    /**
     * 语义搜索技能（仅向量检索）- 分页版本
     *
     * @param query 查询文本
     * @param page 页码（从0开始）
     * @param size 每页大小
     * @return 技能分页结果（按相似度降序）
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Skill> semanticSearch(String query, int page, int size) {
        float[] queryEmbedding = embeddingService.embed(query);

        Query nativeQuery = entityManager.createNativeQuery(
            """
            SELECT s.*, 1 - (s.embedding <=> :query) as similarity
            FROM skill s
            WHERE s.embedding IS NOT NULL
            ORDER BY s.embedding <=> :query
            LIMIT :limit OFFSET :offset
            """, Skill.class
        );
        nativeQuery.setParameter("query", FloatArrayType.toPGvector(queryEmbedding));
        nativeQuery.setParameter("limit", size);
        nativeQuery.setParameter("offset", page * size);
        List<Skill> results = nativeQuery.getResultList();

        Query countQuery = entityManager.createNativeQuery("SELECT COUNT(*) FROM skill WHERE embedding IS NOT NULL");
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(results, PageRequest.of(page, size), total);
    }

    /**
     * 语义搜索技能（仅向量检索）- 返回ID列表
     *
     * @param query 查询文本
     * @param limit 返回数量
     * @return 技能ID列表（按相似度降序）
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> semanticSearch(String query, int limit) {
        float[] queryEmbedding = embeddingService.embed(query);

        Query nativeQuery = entityManager.createNativeQuery(
            """
            SELECT id, 1 - (embedding <=> :query) as similarity
            FROM skill
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
     * 混合搜索技能（向量检索 + 关键词检索）
     *
     * @param query 查询文本
     * @param vectorWeight 向量权重（0-1），关键词权重为 1-vectorWeight
     * @param page 页码（从0开始）
     * @param size 每页大小
     * @return 技能分页结果（按综合相关度降序）
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Page<Skill> hybridSearch(String query, double vectorWeight, int page, int size) {
        float[] queryEmbedding = embeddingService.embed(query);
        double keywordWeight = 1 - vectorWeight;

        Query nativeQuery = entityManager.createNativeQuery(
            """
            SELECT s.*,
                   (COALESCE((1 - (s.embedding <=> :query)), 0) * :vectorWeight +
                    COALESCE(ts_rank(to_tsvector('simple', s.display_name || ' ' || COALESCE(s.summary, '')),
                                    plainto_tsquery('simple', :keyword)), 0) * :keywordWeight) as rank
            FROM skill s
            WHERE s.embedding IS NOT NULL
              AND (s.display_name ILIKE :keywordPattern OR COALESCE(s.summary, '') ILIKE :keywordPattern)
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
            "SELECT COUNT(*) FROM skill s WHERE s.embedding IS NOT NULL AND (s.display_name ILIKE :kp OR COALESCE(s.summary, '') ILIKE :kp)"
        );
        countQuery.setParameter("kp", "%" + query + "%");
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(results, PageRequest.of(page, size), total);
    }

    /**
     * 获取技能向量的相似度
     *
     * @param skillId 技能ID
     * @param query   查询文本
     * @return 相似度分数（0-1，越高越相似）
     */
    @Transactional(readOnly = true)
    public double getSimilarity(Long skillId, String query) {
        float[] queryEmbedding = embeddingService.embed(query);

        Object result = entityManager.createNativeQuery(
            """
            SELECT 1 - (embedding <=> :query) as similarity
            FROM skill
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
    @Transactional(readOnly = true)
    public boolean hasEmbedding(Long skillId) {
        Object result = entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM skill WHERE id = :skillId AND embedding IS NOT NULL"
        ).setParameter("skillId", skillId)
         .getSingleResult();
        return ((Number) result).longValue() > 0;
    }

    /**
     * 删除技能向量
     */
    @Transactional
    public void removeEmbedding(Long skillId) {
        entityManager.createNativeQuery(
            "UPDATE skill SET embedding = NULL WHERE id = ?"
        ).setParameter(1, skillId)
         .executeUpdate();
        log.info("Removed embedding for skill {}", skillId);
    }

    /**
     * 重新索引所有技能
     *
     * @return 成功索引的技能数量
     */
    @Transactional
    public long reindexAll() {
        List<Skill> skills = skillRepository.findAll();
        long count = 0;
        for (Skill skill : skills) {
            try {
                String textToIndex = skill.getDisplayName() + " " + (skill.getSummary() != null ? skill.getSummary() : "");
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