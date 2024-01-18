package com.github.smartcommit.io;

import com.github.smartcommit.model.Group;
import com.github.smartcommit.model.graph.GroupEdge;
import org.jgrapht.io.Attribute;
import org.jgrapht.io.AttributeType;
import org.jgrapht.io.ComponentAttributeProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * @author lsn
 * @date 2024/1/18 5:57 PM
 */
public class GroupTypeProvider implements ComponentAttributeProvider {
    @Override
    public Map<String, Attribute> getComponentAttributes(Object component) {
        if (component instanceof Group) {
            Group group = (Group) component;
            Map<String, Attribute> map = new HashMap<>();
            map.put("color", new GroupColorAttribute(group));
            map.put("shape", new GroupShapeAttribute(group));
            return map;
        }
        if (component instanceof GroupEdge) {
            GroupEdge groupEdge = (GroupEdge) component;
            Map<String, Attribute> map = new HashMap<>();
            map.put("type", new GroupEdgeTypeAttribute(groupEdge));
            map.put("color", new GroupEdgeColorAttribute(groupEdge));
            map.put("style", new GroupEdgeStyleAttribute(groupEdge));
            return map;
        }
        return null;
    }
}

class GroupShapeAttribute implements Attribute {
    private Group group;

    public GroupShapeAttribute(Group group) {
        this.group = group;
    }

    @Override
    public String getValue() {
        return "record";
    }

    @Override
    public AttributeType getType() {
        return AttributeType.STRING;
    }
}

class GroupColorAttribute implements Attribute {
    private Group group;

    public GroupColorAttribute(Group group) {
        this.group = group;
    }

    @Override
    public String getValue() {
        return "blue";
    }

    @Override
    public AttributeType getType() {
        return AttributeType.STRING;
    }
}

class GroupEdgeTypeAttribute implements Attribute {
    private GroupEdge groupEdge;

    public GroupEdgeTypeAttribute(GroupEdge groupEdge) {
        this.groupEdge = groupEdge;
    }

    @Override
    public String getValue() {
        return groupEdge.getType().asString();
    }

    @Override
    public AttributeType getType() {
        return AttributeType.STRING;
    }
}

class GroupEdgeColorAttribute implements Attribute {
    private GroupEdge groupEdge;

    public GroupEdgeColorAttribute(GroupEdge groupEdge) {
        this.groupEdge = groupEdge;
    }

    @Override
    public String getValue() {
        return groupEdge.getType().isConstraint() ? "red" : "black";
    }

    @Override
    public AttributeType getType() {
        return AttributeType.STRING;
    }
}

class GroupEdgeStyleAttribute implements Attribute {
    private GroupEdge groupEdge;

    public GroupEdgeStyleAttribute(GroupEdge groupEdge) {
        this.groupEdge = groupEdge;
    }

    @Override
    public String getValue() {
        return groupEdge.getType().isConstraint() ? "solid" : "dashed";
    }

    @Override
    public AttributeType getType() {
        return AttributeType.STRING;
    }
}
