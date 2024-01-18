package com.github.smartcommit.model.graph;

import com.github.smartcommit.model.diffgraph.DiffEdgeType;

/**
 * @author lsn
 * @date 2024/1/18 3:59 PM
 */
public class GroupEdge {
    private Integer id;
    private GroupEdgeType type;
    private Double weight;

    public GroupEdge(Integer id, GroupEdgeType type, Double weight) {
        this.id = id;
        this.type = type;
        this.weight = weight;
    }

    public Integer getId() {
        return id;
    }

    public GroupEdgeType getType() {
        return type;
    }

    public Double getWeight() {
        return weight;
    }

    public void increaseWeight(double increment) {
        this.weight += increment;
    }

}
