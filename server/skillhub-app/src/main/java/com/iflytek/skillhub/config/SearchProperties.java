package com.iflytek.skillhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索配置属性
 */
@Component
@ConfigurationProperties(prefix = "skillhub.search")
public class SearchProperties {

    private Semantic semantic = new Semantic();

    public Semantic getSemantic() {
        return semantic;
    }

    public void setSemantic(Semantic semantic) {
        this.semantic = semantic;
    }

    public static class Semantic {
        private boolean enabled = true;
        private double weight = 0.35;
        private int candidateMultiplier = 8;
        private int maxCandidates = 120;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public int getCandidateMultiplier() {
            return candidateMultiplier;
        }

        public void setCandidateMultiplier(int candidateMultiplier) {
            this.candidateMultiplier = candidateMultiplier;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }
    }
}
