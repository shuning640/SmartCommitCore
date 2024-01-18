package com.github.smartcommit.model.graph;

/**
 * @author lsn
 * @date 2024/1/18 3:59 PM
 */
public enum GroupEdgeType {
    DEPEND(true, "dependency");

    Boolean fixed;
    String label;

    GroupEdgeType(Boolean fixed, String label) {
        this.fixed = fixed;
        this.label = label;
    }

    public String asString() {
        return this.label;
    }

    public Boolean isConstraint() {
        return this.fixed;
    }
}
