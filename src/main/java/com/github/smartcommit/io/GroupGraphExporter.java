package com.github.smartcommit.io;

import com.github.smartcommit.model.Group;
import com.github.smartcommit.model.diffgraph.DiffEdge;
import com.github.smartcommit.model.diffgraph.DiffNode;
import com.github.smartcommit.model.graph.GroupEdge;
import org.jgrapht.Graph;
import org.jgrapht.io.ComponentAttributeProvider;
import org.jgrapht.io.ComponentNameProvider;
import org.jgrapht.io.DOTExporter;
import org.jgrapht.io.ExportException;

import java.io.StringWriter;
import java.io.Writer;

/**
 * @author lsn
 * @date 2024/1/18 5:40 PM
 */
public class GroupGraphExporter {
    public static String exportAsDotWithType(Graph<Group, GroupEdge> graph) {
        try {
            // use helper classes to define how vertices should be rendered,
            // adhering to the DOT language restrictions
            ComponentNameProvider<Group> vertexIdProvider = Group::getGroupID;
            ComponentNameProvider<Group> vertexLabelProvider = Group -> Group.getIntentLabel() + " " + Group.getGroupID() + " " + Group.getDiffHunkIndices().toString();
//            ComponentNameProvider<Group> vertexLabelProvider = Group -> Group.getIntentLabel().toString();
            ComponentAttributeProvider<Group> vertexAttributeProvider = new GroupTypeProvider();

            ComponentNameProvider<GroupEdge> edgeLabelProvider =
                    edge -> edge.getType().asString() + "(" + edge.getWeight().toString() + ")";
            ComponentAttributeProvider<GroupEdge> edgeAttributeProvider = new GroupTypeProvider();
            org.jgrapht.io.GraphExporter<Group, GroupEdge> exporter =
                    new DOTExporter<>(
                            vertexIdProvider,
                            vertexLabelProvider,
                            edgeLabelProvider,
                            vertexAttributeProvider,
                            edgeAttributeProvider);
            Writer writer = new StringWriter();
            exporter.exportGraph(graph, writer);
            return writer.toString();

        } catch (ExportException e) {
            e.printStackTrace();
            return "";
        }
    }
}
