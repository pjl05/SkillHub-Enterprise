package com.iflytek.skillhub.embedding;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private Query query;

    private VectorSearchService vectorSearchService;

    private Skill testSkill;

    @BeforeEach
    void setUp() {
        vectorSearchService = new VectorSearchService(entityManager, embeddingService, skillRepository);
        testSkill = new Skill(1L, "java-dev", "owner1", SkillVisibility.PUBLIC);
        testSkill.setId(1L);
        testSkill.setDisplayName("Java Development");
        testSkill.setSummary("Java Spring Boot development");
    }

    @Test
    void indexSkill_shouldUpdateEmbedding_whenTextIsValid() {
        Long skillId = 1L;
        String text = "Java Spring Boot";
        float[] embedding = new float[1024];
        embedding[0] = 0.1f;

        when(embeddingService.embed(text)).thenReturn(embedding);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        vectorSearchService.indexSkill(skillId, text);

        verify(entityManager).createNativeQuery(eq("UPDATE skill SET embedding = ? WHERE id = ?"));
    }

    @Test
    void indexSkill_shouldNotUpdate_whenTextIsBlank() {
        vectorSearchService.indexSkill(1L, "");
        verifyNoInteractions(entityManager);
    }

    @Test
    void indexSkill_shouldNotUpdate_whenTextIsNull() {
        vectorSearchService.indexSkill(1L, null);
        verifyNoInteractions(entityManager);
    }

    @Test
    void semanticSearch_withLimit_shouldReturnSkillIds() {
        String queryText = "Java";
        float[] queryEmbedding = new float[1024];

        when(embeddingService.embed(queryText)).thenReturn(queryEmbedding);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setParameter(eq("limit"), eq(10))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(new Object[]{1L, 0.95}, new Object[]{2L, 0.85}));

        List<Long> results = vectorSearchService.semanticSearch(queryText, 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(1L);
        assertThat(results.get(1)).isEqualTo(2L);
    }

    @Test
    void semanticSearch_withPage_shouldReturnPaginatedSkills() {
        String queryText = "Java";
        float[] queryEmbedding = new float[1024];

        when(embeddingService.embed(queryText)).thenReturn(queryEmbedding);
        when(entityManager.createNativeQuery(anyString(), eq(Skill.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setParameter(eq("limit"), eq(20))).thenReturn(query);
        when(query.setParameter(eq("offset"), eq(0))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(testSkill));
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);

        Page<Skill> result = vectorSearchService.semanticSearch(queryText, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(entityManager).createNativeQuery(contains("embedding <=>"), eq(Skill.class));
    }

    @Test
    void hybridSearch_shouldReturnPaginatedSkills() {
        String queryText = "Java Spring";
        float[] queryEmbedding = new float[1024];

        when(embeddingService.embed(queryText)).thenReturn(queryEmbedding);
        when(entityManager.createNativeQuery(anyString(), eq(Skill.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setParameter(eq("limit"), eq(20))).thenReturn(query);
        when(query.setParameter(eq("offset"), eq(0))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(testSkill));
        when(entityManager.createNativeQuery(contains("COUNT"))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);

        Page<Skill> result = vectorSearchService.hybridSearch(queryText, 0.35, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(entityManager).createNativeQuery(contains("ts_rank"), eq(Skill.class));
    }

    @Test
    void hybridSearch_shouldUseCorrectWeights() {
        String queryText = "test";
        float[] queryEmbedding = new float[1024];

        when(embeddingService.embed(queryText)).thenReturn(queryEmbedding);
        when(entityManager.createNativeQuery(anyString(), eq(Skill.class))).thenReturn(query);
        when(query.setParameter(eq("vectorWeight"), eq(0.7))).thenReturn(query);
        when(query.setParameter(eq("keywordWeight"), eq(0.3))).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.emptyList());
        when(entityManager.createNativeQuery(contains("COUNT"))).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);

        vectorSearchService.hybridSearch(queryText, 0.7, 0, 20);

        verify(query).setParameter(eq("vectorWeight"), eq(0.7));
        verify(query).setParameter(eq("keywordWeight"), eq(0.3));
    }

    @Test
    void hasEmbedding_shouldReturnTrue_whenEmbeddingExists() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);

        boolean result = vectorSearchService.hasEmbedding(1L);

        assertThat(result).isTrue();
    }

    @Test
    void hasEmbedding_shouldReturnFalse_whenNoEmbedding() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);

        boolean result = vectorSearchService.hasEmbedding(1L);

        assertThat(result).isFalse();
    }

    @Test
    void removeEmbedding_shouldUpdateSkill() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        vectorSearchService.removeEmbedding(1L);

        verify(entityManager).createNativeQuery("UPDATE skill SET embedding = NULL WHERE id = ?");
    }

    @Test
    void reindexAll_shouldReindexAllSkills() {
        Skill skill1 = new Skill(1L, "java-dev", "owner1", SkillVisibility.PUBLIC);
        skill1.setId(1L);
        skill1.setDisplayName("Java Development");
        skill1.setSummary("Java Spring Boot");

        Skill skill2 = new Skill(2L, "python-dev", "owner2", SkillVisibility.PUBLIC);
        skill2.setId(2L);
        skill2.setDisplayName("Python Development");
        skill2.setSummary("Python Django");

        when(skillRepository.findAll()).thenReturn(List.of(skill1, skill2));
        when(embeddingService.embed(anyString())).thenReturn(new float[1024]);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        long count = vectorSearchService.reindexAll();

        assertThat(count).isEqualTo(2);
        verify(embeddingService, times(2)).embed(anyString());
    }

    @Test
    void getSimilarity_shouldReturnSimilarityScore() {
        float[] queryEmbedding = new float[1024];
        when(embeddingService.embed("Java")).thenReturn(queryEmbedding);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0.85);

        double similarity = vectorSearchService.getSimilarity(1L, "Java");

        assertThat(similarity).isEqualTo(0.85);
    }
}