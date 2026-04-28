package com.iflytek.skillhub.domain.skill.dto;

import com.iflytek.skillhub.domain.skill.Skill;

import java.math.BigDecimal;

/**
 * Basic skill value object for search results
 */
public record SkillBasicVO(
        Long id,
        String slug,
        String displayName,
        String summary,
        String ownerId,
        String visibility,
        Long downloadCount,
        Integer starCount,
        BigDecimal ratingAvg
) {
    public static SkillBasicVO fromEntity(Skill skill) {
        return new SkillBasicVO(
                skill.getId(),
                skill.getSlug(),
                skill.getDisplayName(),
                skill.getSummary(),
                skill.getOwnerId(),
                skill.getVisibility() != null ? skill.getVisibility().name() : null,
                skill.getDownloadCount(),
                skill.getStarCount(),
                skill.getRatingAvg()
        );
    }
}