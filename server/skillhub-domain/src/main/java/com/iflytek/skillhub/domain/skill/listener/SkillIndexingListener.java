package com.iflytek.skillhub.domain.skill.listener;

import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.embedding.VectorSearchService;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * JPA entity listener that auto-indexes skills when they are published or updated.
 */
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
        if (vectorSearchService == null) {
            return;
        }
        try {
            // Only index ACTIVE (published) skills
            if (skill.getStatus() != null && "ACTIVE".equals(skill.getStatus().name())) {
                String textToIndex = skill.getDisplayName() + " " +
                    (skill.getSummary() != null ? skill.getSummary() : "");
                vectorSearchService.indexSkill(skill.getId(), textToIndex);
                log.info("Auto-indexed skill {} after {}", skill.getId(), skill.getStatus());
            }
        } catch (Exception e) {
            log.error("Failed to auto-index skill {}", skill.getId(), e);
        }
    }
}