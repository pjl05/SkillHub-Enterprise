package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.config.SearchProperties;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.dto.SkillBasicVO;
import com.iflytek.skillhub.embedding.VectorSearchService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Semantic and hybrid search controller for skills using vector embeddings.
 * Provides semantic search, hybrid search, and index management endpoints.
 */
@RestController
@RequestMapping("/api/v1/skills/search")
public class SemanticSearchController extends BaseApiController {

    private final VectorSearchService vectorSearchService;
    private final SearchProperties searchProperties;

    public SemanticSearchController(VectorSearchService vectorSearchService,
                                     SearchProperties searchProperties,
                                     com.iflytek.skillhub.dto.ApiResponseFactory responseFactory) {
        super(responseFactory);
        this.vectorSearchService = vectorSearchService;
        this.searchProperties = searchProperties;
    }

    /**
     * Semantic search using vector embeddings only
     */
    @GetMapping("/semantic")
    public com.iflytek.skillhub.dto.ApiResponse<Page<SkillBasicVO>> semanticSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Skill> skills = vectorSearchService.semanticSearch(query, page, size);
        return ok("response.success.read", skills.map(SkillBasicVO::fromEntity));
    }

    /**
     * Hybrid search combining vector and keyword search
     */
    @GetMapping("/hybrid")
    public com.iflytek.skillhub.dto.ApiResponse<Page<SkillBasicVO>> hybridSearch(
            @RequestParam String query,
            @RequestParam(name = "vector_weight", defaultValue = "0.35") double vectorWeight,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Skill> skills = vectorSearchService.hybridSearch(query, vectorWeight, page, size);
        return ok("response.success.read", skills.map(SkillBasicVO::fromEntity));
    }

    /**
     * Reindex a single skill
     */
    @PostMapping("/{skillId}/reindex")
    public com.iflytek.skillhub.dto.ApiResponse<Void> reindexSkill(
            @PathVariable Long skillId,
            @RequestParam(required = false) String text) {
        vectorSearchService.indexSkill(skillId, text != null ? text : "");
        return ok("response.success.update", null);
    }

    /**
     * Reindex all skills
     */
    @PostMapping("/reindex-all")
    public com.iflytek.skillhub.dto.ApiResponse<BatchIndexResult> reindexAll() {
        long count = vectorSearchService.reindexAll();
        return ok("response.success.update", new BatchIndexResult(count));
    }

    /**
     * Get similarity score between a skill and a query
     */
    @GetMapping("/{skillId}/similarity")
    public com.iflytek.skillhub.dto.ApiResponse<Double> getSimilarity(
            @PathVariable Long skillId,
            @RequestParam String query) {
        return ok("response.success.read", vectorSearchService.getSimilarity(skillId, query));
    }

    /**
     * Check if a skill has an embedding vector
     */
    @GetMapping("/{skillId}/has-embedding")
    public com.iflytek.skillhub.dto.ApiResponse<Boolean> hasEmbedding(@PathVariable Long skillId) {
        return ok("response.success.read", vectorSearchService.hasEmbedding(skillId));
    }

    public record BatchIndexResult(long indexedCount) {}
}