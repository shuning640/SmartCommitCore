package com.github.smartcommit.core;

import com.github.smartcommit.io.DiffGraphExporter;
import com.github.smartcommit.model.Action;
import com.github.smartcommit.model.DiffFile;
import com.github.smartcommit.model.DiffHunk;
import com.github.smartcommit.model.Group;
import com.github.smartcommit.model.constant.*;
import com.github.smartcommit.model.diffgraph.DiffEdge;
import com.github.smartcommit.model.diffgraph.DiffEdgeType;
import com.github.smartcommit.model.diffgraph.DiffNode;
import com.github.smartcommit.model.graph.Edge;
import com.github.smartcommit.model.graph.Node;
import com.github.smartcommit.util.Utils;
import com.zhixiangli.code.similarity.CodeSimilarity;
import com.zhixiangli.code.similarity.strategy.CosineSimilarity;
import gr.uom.java.xmi.UMLModel;
import gr.uom.java.xmi.UMLModelASTReader;
import gr.uom.java.xmi.diff.CodeRange;
import gr.uom.java.xmi.diff.UMLModelDiff;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.builder.GraphTypeBuilder;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringMinerTimedOutException;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.smartcommit.io.DataCollector.analyzeASTActions;

public class GroupGenerator {
  private static final Logger logger = Logger.getLogger(GroupGenerator.class);

  // meta data
  private String repoID;
  private String repoName;
  private Pair<String, String> srcDirs; // dirs to store the collected files
  private List<DiffFile> diffFiles;
  private List<DiffHunk> diffHunks;
  private Graph<Node, Edge> baseGraph;
  private Graph<Node, Edge> currentGraph;
  // map from the grouped diff hunk index to its group id (for quick-find)
  private Map<String, String> indexToGroupMap;

  // outputs
  private Graph<DiffNode, DiffEdge> diffGraph;

  // options
  private boolean processNonJava = false;
  private boolean detectRefs = false;
  private double minSimilarity = 0.618D;
  private int maxDistance = 0;

  //
  Set<Integer> weakDependEdges = new HashSet<>();
  Set<String> generalNodes = new HashSet<>();

  public GroupGenerator(){

  }
  public GroupGenerator(
      String repoID,
      String repoName,
      Pair<String, String> srcDirs,
      List<DiffFile> diffFiles,
      List<DiffHunk> diffHunks,
      Graph<Node, Edge> baseGraph,
      Graph<Node, Edge> currentGraph) {
    this.repoID = repoID;
    this.repoName = repoName;
    this.srcDirs = srcDirs;
    this.diffFiles = diffFiles;
    this.diffHunks = diffHunks;
    this.baseGraph = baseGraph;
    this.currentGraph = currentGraph;

    this.indexToGroupMap = new HashMap<>();
    this.diffGraph = initDiffGraph();
  }

  // hard -- topo sort + union find
  // soft -- similarity, distance
  // cross-version -- move, ref
  // cross-lang ref -- references

  private Graph<DiffNode, DiffEdge> initDiffGraph() {
    Graph<DiffNode, DiffEdge> diffGraph =
        GraphTypeBuilder.<DiffNode, DiffEdge>directed()
            .allowingMultipleEdges(true)
            .allowingSelfLoops(true)
            .edgeClass(DiffEdge.class)
            .weighted(true)
            .buildGraph();
    int nodeID = 0;

    List<Node> baseHunkNodes =
        baseGraph.vertexSet().stream()
            .filter(node -> node.isInDiffHunk)
            .collect(Collectors.toList());
    List<Node> currentHunkNodes =
        currentGraph.vertexSet().stream()
            .filter(node -> node.isInDiffHunk)
            .collect(Collectors.toList());

    for (DiffHunk diffHunk : diffHunks) {
      DiffNode diffNode = new DiffNode(nodeID++, diffHunk.getUniqueIndex(), diffHunk.getUUID());
      if (diffHunk.getFileType().equals(FileType.JAVA)) {
        Map<String, Integer> baseHierarchy =
            getHierarchy(baseGraph, baseHunkNodes, diffHunk.getUniqueIndex());
        Map<String, Integer> currentHierarchy =
            getHierarchy(currentGraph, currentHunkNodes, diffHunk.getUniqueIndex());
        if (!baseHierarchy.isEmpty()) {
          diffNode.setBaseHierarchy(baseHierarchy);
        }
        if (!currentHierarchy.isEmpty()) {
          diffNode.setCurrentHierarchy(currentHierarchy);
        }
      }

      diffGraph.addVertex(diffNode);
    }
    return diffGraph;
  }

  /** Build edges in the diff graph */
  //todo 计算edge weight的主要函数
  public String buildDiffGraph() {

    List<DiffFile> nonJavaDiffFiles =
        diffFiles.stream()
            .filter(diffFile -> !diffFile.getFileType().equals(FileType.JAVA))
            .collect(Collectors.toList());
    Set<DiffHunk> others = new TreeSet<>(diffHunkComparator());
    if (processNonJava) {
      for (DiffFile diffFile : nonJavaDiffFiles) {
        others.addAll(diffFile.getDiffHunks());
      }
      createEdges(others, DiffEdgeType.OTHERS, 1.0);
    } else {
      Map<String, Set<DiffHunk>> diffHunksByFileType = new HashMap<>();
      for (DiffFile diffFile : nonJavaDiffFiles) {
        String fileType =
            diffFile.getBaseRelativePath().isEmpty()
                ? Utils.getFileExtension(diffFile.getBaseRelativePath())
                : Utils.getFileExtension(diffFile.getCurrentRelativePath());
        if (!diffHunksByFileType.containsKey(fileType)) {
          diffHunksByFileType.put(fileType, new HashSet<>());
        }
        diffHunksByFileType.get(fileType).addAll(diffFile.getDiffHunks());
      }
      for (Map.Entry<String, Set<DiffHunk>> entry : diffHunksByFileType.entrySet()) {
        // group file according to file type
        createEdges(entry.getValue(), DiffEdgeType.NONJAVA, 1.0);
      }
    }

    Map<Integer, Set<DiffHunk>> refactorMap = new HashMap<>();
    Set<DiffHunk> refDiffHunks = new TreeSet<>(diffHunkComparator());
    // refactor
    if (detectRefs) {
      ExecutorService service = Executors.newSingleThreadExecutor();
      Future<?> f = null;
      try {
        Runnable r = () -> detectRefactorings(refDiffHunks);
        f = service.submit(r);
        f.get(300, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        f.cancel(true);
        logger.warn("Ignore refactoring detection due to RM timeout: ", e);
      } catch (ExecutionException | InterruptedException e) {
        logger.warn("Ignore refactoring detection due to RM error: ", e);
        e.printStackTrace();
      } finally {
        service.shutdown();
      }
    }

    Set<DiffHunk> testDiffHunks = new TreeSet<>(diffHunkComparator());
    Set<DiffHunk> reformat = new TreeSet<>(diffHunkComparator());
    for (int i = 0; i < diffHunks.size(); ++i) {
      DiffHunk diffHunk = diffHunks.get(i);
      diffHunk.setAstActions(analyzeASTActions(diffHunk));
      diffHunk.generateDescription();

      if(!diffHunk.getFileType().equals(FileType.JAVA)){
        continue;
      }
      //test
      if(detectTest(diffHunk)){
        testDiffHunks.add(diffHunk);
        continue;
      }
      //reformat
      if (detectReformatting(diffHunk)) {
        reformat.add(diffHunk);
        continue;
      }
    }

    // cache all links from base/current graph as a top order
    Map<String, Set<String>> hardLinks =
            Utils.mergeTwoMaps(analyzeDefUse(baseGraph), analyzeDefUse(currentGraph));
    // remove testDiffHunks、 others和 reformat key&value in hardLinks
    removeEntriesRelatedToDiffHunks(hardLinks, testDiffHunks, reformat, others);

    generalNodes = getGeneralNodes(hardLinks);

    for (int i = 0; i < diffHunks.size(); ++i) {
      DiffHunk diffHunk = diffHunks.get(i);
      if(!diffHunk.getFileType().equals(FileType.JAVA) || testDiffHunks.contains(diffHunk) || reformat.contains(diffHunk)){
        continue;
      }
      // create edge according to hard links (that depends on the current)
      // in topo order
      if (hardLinks.containsKey(diffHunk.getUniqueIndex())) {
        for (String target : hardLinks.get(diffHunk.getUniqueIndex())) {
          // target不能是test或者reformat
          if (!target.equals(diffHunk.getUniqueIndex()) && testDiffHunks.stream().noneMatch(hunk -> target.equals(hunk.getUniqueIndex()))
                  && reformat.stream().noneMatch(hunk -> target.equals(hunk.getUniqueIndex()))){
            if(generalNodes.contains(target)){
              createEdge(diffHunk.getUniqueIndex(), target, DiffEdgeType.WEAKDEPEND, 0.1);
              weakDependEdges.add(diffGraph.edgeSet().size());
            }else{
              createEdge(diffHunk.getUniqueIndex(), target, DiffEdgeType.DEPEND, 1.0);
            }
          }
        }
      }

      // estimate soft links (every two diff hunks)
      for (int j = i + 1; j < diffHunks.size(); j++) {
        DiffHunk diffHunk1 = diffHunks.get(j);
        if (!diffHunk1.getUniqueIndex().equals(diffHunk.getUniqueIndex())) {
          // similarity (textual)
          double similarity = detectSimilarity(diffHunk, diffHunk1);
          if (similarity >= minSimilarity) {
            createEdge(
                diffHunk.getUniqueIndex(),
                diffHunk1.getUniqueIndex(),
                DiffEdgeType.SIMILAR,
                similarity);
          }
          // distance (1/n)
          int distance = detectProxmity(diffHunk, diffHunk1);
          if (distance > 0 && distance <= maxDistance) {
            createEdge(
                diffHunk.getUniqueIndex(),
                diffHunk1.getUniqueIndex(),
                DiffEdgeType.CLOSE,
                Utils.formatDouble((double) 1 / distance));
          }
          //move：
          if (diffHunk.getFileIndex().equals(diffHunk1.getFileIndex())) {
            if(detectMove(diffHunk, diffHunk1)){
                createEdge(
                        diffHunk.getUniqueIndex(),
                        diffHunk1.getUniqueIndex(),
                        DiffEdgeType.MOVING,
                        1.0);
            }
          }
        }
        // TODO: cross-lang dependency
        // detect references between configs and java
      }
    }
    createEdges(testDiffHunks, DiffEdgeType.TEST, 1.0);
    createEdges(reformat, DiffEdgeType.REFORMAT, 1.0);
    Stream.of(testDiffHunks, reformat, others /*, 更多集合*/).flatMap(Set::stream).forEach(refDiffHunks::remove);
    if (!refDiffHunks.isEmpty()) {
      // detect same action diffHunk
      for (DiffHunk diffHunk : refDiffHunks) {
        boolean foundGroup = false;
        for (Map.Entry<Integer, Set<DiffHunk>> entry : refactorMap.entrySet()) {
          for (DiffHunk diffHunk1 : entry.getValue()) {
            if (detectRefAction(diffHunk, diffHunk1).size() > 0) {
              entry.getValue().add(diffHunk);
              foundGroup = true;
              break;
            }
          }
          if (foundGroup) {
            break;
          }
        }
        // if no similar diffHunk add new Set<DiffHunk>
        if (!foundGroup) {
          Set<DiffHunk> newSet = new TreeSet<>(diffHunkComparator());
          newSet.add(diffHunk);
          refactorMap.put(refactorMap.size() + 1, newSet);
        }
      }
      for (Map.Entry<Integer, Set<DiffHunk>> entry : refactorMap.entrySet()) {
        createEdges(entry.getValue(), DiffEdgeType.REFACTOR, 1.0);
      }
    }
    return DiffGraphExporter.exportAsDotWithType(diffGraph);
  }

  private Map<String, Set<String>> analyzeDefUse(Graph<Node, Edge> graph) {
    Map<String, Set<String>> defUseLinks = new HashMap<>();
    List<Node> hunkNodes =
        graph.vertexSet().stream().filter(node -> node.isInDiffHunk).collect(Collectors.toList());
    for (Node node : hunkNodes) {
      List<String> defHunkNodes = analyzeDef(graph, node, new HashSet<>());
      List<String> useHunkNodes = analyzeUse(graph, node, new HashSet<>());
      // record the links an return
      if (!defHunkNodes.isEmpty() || !useHunkNodes.isEmpty()) {
        if (!defUseLinks.containsKey(node.diffHunkIndex)) {
          defUseLinks.put(node.diffHunkIndex, new HashSet<>());
        }
        for (String s : defHunkNodes) {
          defUseLinks.get(node.diffHunkIndex).add(s);
        }
        for (String s : useHunkNodes) {
          defUseLinks.get(node.diffHunkIndex).add(s);
        }
      }
    }
    return defUseLinks;
  }

  /**
   * DFS to find all nodes point to the current in diff hunks
   *
   * @param graph
   * @param node
   * @param visited
   * @return
   */
  private List<String> analyzeDef(Graph<Node, Edge> graph, Node node, HashSet<Node> visited) {
    //    Graphs.predecessorListOf()
    List<String> res = new ArrayList<>();
    Set<Edge> inEdges =
        graph.incomingEdgesOf(node).stream()
            .filter(edge -> edge.getType().isStructural())
            .collect(Collectors.toSet());
    if (inEdges.isEmpty() || visited.contains(node)) {
      return res;
    }
    visited.add(node);
    for (Edge edge : inEdges) {
      Node srcNode = graph.getEdgeSource(edge);
      if (srcNode != node && !visited.contains(node)) {
        if (srcNode.isInDiffHunk) {
          res.add(srcNode.diffHunkIndex);
        }
        res.addAll(analyzeDef(graph, srcNode, visited));
      }
    }
    return res;
  }

  /**
   * DFS to find all nodes that start from the current in diff hunks
   *
   * @param graph
   * @param node
   * @param visited
   * @return
   */
  private List<String> analyzeUse(Graph<Node, Edge> graph, Node node, HashSet<Node> visited) {
    List<String> res = new ArrayList<>();
    Set<Edge> outEdges =
        graph.outgoingEdgesOf(node).stream()
            .filter(edge -> !edge.getType().isStructural())
            .collect(Collectors.toSet());
    if (outEdges.isEmpty() || visited.contains(node)) {
      return res;
    }
    visited.add(node);
    for (Edge edge : outEdges) {
      Node tgtNode = graph.getEdgeTarget(edge);
      if (tgtNode != node && !visited.contains(tgtNode)) {
        if (tgtNode.isInDiffHunk) {
          res.add(tgtNode.diffHunkIndex);
        }
        res.addAll(analyzeUse(graph, tgtNode, visited));
      }
    }
    return res;
  }

  public Map<String, Group> generateSimpleGroups(){
    indexToGroupMap.clear();
    Map<String, Group> result = new LinkedHashMap<>(); // id:Group
    Set<DiffNode> othersDiffNode = new TreeSet<>(diffNodeComparator());
    Set<DiffNode> keyDiffNode = new TreeSet<>(diffNodeComparator());
    for(DiffEdge edge : diffGraph.edgeSet()){
      if(edge.getType().equals(DiffEdgeType.REFORMAT) || edge.getType().equals(DiffEdgeType.TEST)
//              || edge.getType().equals(DiffEdgeType.NONJAVA) || edge.getType().equals(DiffEdgeType.DOC)
//              || edge.getType().equals(DiffEdgeType.CONFIG) || edge.getType().equals(DiffEdgeType.RESOURCE)
//              || edge.getType().equals(DiffEdgeType.OTHERS)
      ){
        DiffNode target = diffGraph.getEdgeTarget(edge);
        DiffNode source = diffGraph.getEdgeSource(edge);
        othersDiffNode.add(target);
        othersDiffNode.add(source);
      }
    }
    createGroup(result, othersDiffNode, new HashSet<>(), GroupLabel.OTHER);

    for (DiffHunk diffHunk : diffHunks) {
      if (!indexToGroupMap.containsKey(diffHunk.getUniqueIndex())) {
        keyDiffNode.add(findNodeByIndex(diffHunk.getUniqueIndex()));
      }
    }
    createGroup(result, keyDiffNode, new HashSet<>(), GroupLabel.FEATURE);

    return result;
  }


  /**
   * Generate groups of changes either with a dynamic or fixed threshold
   * todo
   * @param threshold
   */
  public Map<String, Group> generateGroups(Double threshold) {
    //    String diffGraphString = DiffGraphExporter.exportAsDotWithType(diffGraph);
    Map<String, Group> result = new LinkedHashMap<>(); // id:Group
    // save the edge info for intent classification
    List<DiffEdgeType> edgeTypes = new ArrayList<>();
    Set<Integer> linkCategories = new HashSet<>();

    // sort edges by weight, srcindex, dstindex
    Comparator<DiffEdge> comparator = (o1, o2) -> o2.getWeight().compareTo(o1.getWeight());
    Queue<DiffEdge> pq = new PriorityQueue<>(comparator);
    List<DiffEdge> edgeList = new ArrayList<>();

    if (threshold <= 0) {
      // use dynamic threshold
      // fill into the PQ
      for (DiffEdge edge : diffGraph.edgeSet()) {
        pq.offer(edge);
      }
      List<DiffEdge> temp = new ArrayList<>();
      while (!pq.isEmpty()) {
        temp.add(pq.poll());
      }
      //  compute delta and remember max-gap index
      double maxGap = 0;
      int maxGapIndex = -1;
      for (int i = 0; i < temp.size() - 1; ++i) {
        double delta = temp.get(i).getWeight() - temp.get(i + 1).getWeight();
        if (delta > maxGap) {
          maxGap = delta;
          maxGapIndex = i;
        }
      }
      //    drop edges under the max-gap
      for (int i = 0; i <= maxGapIndex; ++i) {
        edgeList.add(temp.get(i));
      }
    } else { // use user-provided threshold
      for (DiffEdge edge : diffGraph.edgeSet()) {
        if (edge.getWeight() >= threshold) {
          pq.offer(edge);
        }
        while (!pq.isEmpty()) {
          edgeList.add(pq.poll());
        }
      }
    }

    for (DiffEdge edge : edgeList) {
      linkCategories.add(edge.getType().getCategory());
      edgeTypes.add(edge.getType());

      DiffNode source = diffGraph.getEdgeSource(edge);
      DiffNode target = diffGraph.getEdgeTarget(edge);
      if (indexToGroupMap.containsKey(source.getIndex())
          && indexToGroupMap.containsKey(target.getIndex())) {
        String gID1 = indexToGroupMap.get(source.getIndex());
        String gID2 = indexToGroupMap.get(target.getIndex());
        if (!gID1.equals(gID2)) {
          // merge the later to the former group
          Group g1 = result.get(indexToGroupMap.get(source.getIndex()));
          Group g2 = result.get(indexToGroupMap.get(target.getIndex()));
          if (Integer.parseInt(gID1.substring(5)) > Integer.parseInt(gID2.substring(5))) {
            mergeGroups(result, g1, g2);
          } else {
            mergeGroups(result, g2, g1);
          }
        }
      } else {
        // add the later to the former group
        if (indexToGroupMap.containsKey(source.getIndex())) {
          addToGroup(target, result.get(indexToGroupMap.get(source.getIndex())));
        } else if (indexToGroupMap.containsKey(target.getIndex())) {
          addToGroup(source, result.get(indexToGroupMap.get(target.getIndex())));
        } else {
          // both not exist, create new group
          Set<DiffNode> nodes = new TreeSet<>(diffNodeComparator());
          nodes.add(source);
          nodes.add(target);
          createGroup(result, nodes, linkCategories, getIntentFromEdges(edgeTypes));
          linkCategories.clear();
          edgeTypes.clear();
        }
      }
    }

    Set<DiffNode> individuals = new TreeSet<>(diffNodeComparator());
    for (DiffHunk diffHunk : diffHunks) {
      if (!indexToGroupMap.containsKey(diffHunk.getUniqueIndex()) && !generalNodes.contains(diffHunk.getUniqueIndex())) {
        individuals.add(findNodeByIndex(diffHunk.getUniqueIndex()));
      }
    }
    //    assignIndividuals(result, individuals);
    // 对剩余单个hunk的处理为按文件成组
    Map<String, Set<DiffNode>> groupByFile = new HashMap<>();
    for (DiffNode node : individuals) {
      String fileIndex = node.getFileIndex().toString();
      groupByFile.putIfAbsent(fileIndex, new HashSet<>());
      groupByFile.get(fileIndex).add(node);
    }
    for (Map.Entry<String, Set<DiffNode>> entry : groupByFile.entrySet()) {
      createGroup(result, entry.getValue(), new HashSet<>(), GroupLabel.OTHER);
    }

    assignGeneralNodes(result, generalNodes, weakDependEdges);

    return result;
  }

  /**
   * Accept a filter to mask one specific link and regenerate the result
   *
   * @param threshold
   */
  public Map<String, Group> generateGroups(Double threshold, int... filters) {

    Map<String, Group> result = new LinkedHashMap<>(); // generated groups, id:Group
    Set<Integer> filteredCategories = new HashSet<>();
    for (int f : filters) {
      filteredCategories.add(f);
    }

    // add edges to priority queue
    Comparator<DiffEdge> comparator = (o1, o2) -> o2.getWeight().compareTo(o1.getWeight());
    Queue<DiffEdge> pq = new PriorityQueue<>(comparator);
    for (DiffEdge edge : diffGraph.edgeSet()) {
      // drop/mask specific types of links
      if (!filteredCategories.contains(edge.getType().getCategory())
          && edge.getWeight() >= threshold) {
        pq.offer(edge);
      }
    }

    HashSet<Integer> linkCategories = new HashSet<>();
    while (!pq.isEmpty()) {
      DiffEdge edge = pq.poll();
      linkCategories.add(edge.getType().getCategory());

      DiffNode source = diffGraph.getEdgeSource(edge);
      DiffNode target = diffGraph.getEdgeTarget(edge);
      if (indexToGroupMap.containsKey(source.getIndex())
          && indexToGroupMap.containsKey(target.getIndex())) {
        String gID1 = indexToGroupMap.get(source.getIndex());
        String gID2 = indexToGroupMap.get(target.getIndex());
        if (!gID1.equals(gID2)) {
          // merge the later to the former group
          Group g1 = result.get(indexToGroupMap.get(source.getIndex()));
          Group g2 = result.get(indexToGroupMap.get(target.getIndex()));
          if (Integer.parseInt(gID1.substring(5)) > Integer.parseInt(gID2.substring(5))) {
            mergeGroups(result, g1, g2);
          } else {
            mergeGroups(result, g2, g1);
          }
        }
      } else {
        // add the later to the former group
        if (indexToGroupMap.containsKey(source.getIndex())) {
          addToGroup(target, result.get(indexToGroupMap.get(source.getIndex())));
        } else if (indexToGroupMap.containsKey(target.getIndex())) {
          addToGroup(source, result.get(indexToGroupMap.get(target.getIndex())));
        } else {
          // both not exist, create new group
          Set<DiffNode> nodes = new TreeSet<>(diffNodeComparator());
          nodes.add(source);
          nodes.add(target);
          createGroup(result, nodes, linkCategories, GroupLabel.OTHER);
        }
      }
    }

    Set<DiffNode> individuals = new TreeSet<>(diffNodeComparator());
    for (DiffHunk diffHunk : diffHunks) {
      if (!indexToGroupMap.containsKey(diffHunk.getUniqueIndex())) {
        individuals.add(findNodeByIndex(diffHunk.getUniqueIndex()));
      }
    }
    assignIndividuals(result, individuals);
    Map<String, Set<DiffNode>> groupByFile = new HashMap<>();
    for (DiffNode node : individuals) {
      String fileIndex = node.getFileIndex().toString();
      groupByFile.putIfAbsent(fileIndex, new HashSet<>());
      groupByFile.get(fileIndex).add(node);
    }
    for (Map.Entry<String, Set<DiffNode>> entry : groupByFile.entrySet()) {
      createGroup(result, entry.getValue(), new HashSet<>(), GroupLabel.OTHER);
    }

    return result;
  }

  /**
   * Baseline: cluster changes only according to def-use and use-use !WARN!: will remove other edges
   * except hard constraints, so must be called after generateGroups()
   *
   * @return
   */
  public Map<String, Group> clusterChanges() {
    Map<String, Group> result = new LinkedHashMap<>();

    // remove non-def-use edges from the diff hunk graph
    Set<DiffEdge> edges = new HashSet<>(diffGraph.edgeSet());
    for (DiffEdge edge : edges) {
      if (!edge.getType().equals(DiffEdgeType.DEPEND)
          && !edge.getType().equals(DiffEdgeType.TEST)) {
        diffGraph.removeEdge(edge);
      }
    }
    Set<Integer> linkCategories = new HashSet<>();
    linkCategories.add(DiffEdgeType.DEPEND.getCategory());
    linkCategories.add(DiffEdgeType.TEST.getCategory());

    Set<DiffNode> individuals = new LinkedHashSet<>();
    ConnectivityInspector inspector = new ConnectivityInspector(diffGraph);
    List<Set<DiffNode>> connectedSets = inspector.connectedSets();
    for (Set<DiffNode> diffNodesSet : connectedSets) {
      if (diffNodesSet.size() == 1) {
        // individual
        individuals.addAll(diffNodesSet);
      } else if (diffNodesSet.size() > 1) {
        // sort and deduplicate
        Set<DiffNode> diffNodes =
            diffNodesSet.stream()
                .sorted(diffNodeComparator())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        createGroup(result, diffNodes, linkCategories, GroupLabel.OTHER);
      }
    }

    assignIndividuals(result, individuals);
    createGroup(result, individuals, new HashSet<>(), GroupLabel.OTHER);
    return result;
  }

  /**
   * Simply decompose from connected components
   *
   * @deprecated
   * @return
   */
  public Map<String, Group> decomposeByConnectivity() {
    Map<String, Group> result = new LinkedHashMap<>();
    Set<DiffNode> individuals = new LinkedHashSet<>();
    Map<String, String> idToIndexMap = new HashMap<>();
    ConnectivityInspector inspector = new ConnectivityInspector(diffGraph);
    List<Set<DiffNode>> connectedSets = inspector.connectedSets();
    for (Set<DiffNode> diffNodesSet : connectedSets) {
      if (diffNodesSet.size() == 1) {
        // individual
        diffNodesSet.forEach(
            diffNode -> {
              individuals.add(diffNode);
              idToIndexMap.put(diffNode.getUUID(), diffNode.getIndex());
            });
      } else if (diffNodesSet.size() > 1) {
        Set<DiffNode> diffNodes = new LinkedHashSet<>();
        // sort and deduplicate
        diffNodesSet =
            diffNodesSet.stream()
                .sorted(diffNodeComparator())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<DiffEdgeType> edgeTypes = new ArrayList<>();
        for (DiffNode diffNode : diffNodesSet) {
          diffNodes.add(diffNode);
          diffGraph
              .outgoingEdgesOf(diffNode)
              .forEach(diffEdge -> edgeTypes.add(diffEdge.getType()));
        }
        // get the most frequent edge type as the group label
        createGroup(result, diffNodes, new HashSet<>(), getIntentFromEdges(edgeTypes));
      }
    }

    assignIndividuals(result, individuals);
    // group individuals with file type centrality
    if (individuals.size() <= 3) {
      for (DiffNode node : individuals) {
        createGroup(result, new HashSet<>(Arrays.asList(node)), new HashSet<>(), GroupLabel.OTHER);
      }
    } else {
      Map<String, Set<DiffNode>> groupByFileType = new HashMap<>();
      for (DiffNode node : individuals) {
        DiffFile file =
            diffFiles.stream()
                .filter(diffFile -> diffFile.getIndex().equals(node.getFileIndex()))
                .findFirst()
                .get();
        String fileType =
            file.getBaseRelativePath().isEmpty()
                ? Utils.getFileExtension(file.getBaseRelativePath())
                : Utils.getFileExtension(file.getCurrentRelativePath());
        if (!groupByFileType.containsKey(fileType)) {
          groupByFileType.put(fileType, new HashSet<>());
        }
        groupByFileType.get(fileType).add(node);
      }
      for (Map.Entry<String, Set<DiffNode>> entry : groupByFileType.entrySet()) {
        createGroup(result, entry.getValue(), new HashSet<>(), GroupLabel.OTHER);
      }
    }
    return result;
  }

  private GroupLabel getIntentFromEdges(List<DiffEdgeType> edgeTypes) {
    if (edgeTypes.contains(DiffEdgeType.REFACTOR)) {
      return GroupLabel.REFACTOR;
    }
    DiffEdgeType edgeType = Utils.mostCommon(edgeTypes);
    switch (edgeType) {
      case SIMILAR:
        return GroupLabel.FIX;
      case MOVING:
      case REFORMAT:
        return GroupLabel.REFORMAT;
      case DOC:
        if (processNonJava) {
          return GroupLabel.DOC;
        }
      case RESOURCE:
        if (processNonJava) {
          return GroupLabel.RESOURCE;
        }
      case CONFIG:
        if (processNonJava) {
          return GroupLabel.CONFIG;
        }
      case NONJAVA:
        return GroupLabel.NONJAVA;
      case CLOSE:
      case DEPEND:
      default:
        // TODO: only consider groups including new declaration nodes as new feature
        return GroupLabel.FEATURE;
        // TODO: consider groups including simply add test cases/methods changes as test
        // GroupLabel.TEST
    }
  }

  /** Create a new group to group given diff hunks */
  private String createGroup(
      Map<String, Group> groups,
      Set<DiffNode> diffNodes,
      Set<Integer> linkCategories,
      GroupLabel intent) {
    if (!diffNodes.isEmpty()) {
      Integer maxInt = -1;
      for (String k : groups.keySet()) {
        maxInt =
            Integer.parseInt(k.substring(5)) > maxInt ? Integer.parseInt(k.substring(5)) : maxInt;
      }
      String groupID = "group" + (maxInt + 1);
      List<String> diffHunkIndices = new ArrayList<>();
      List<String> diffHunkIDs = new ArrayList<>();
      for (DiffNode node : diffNodes) {
        diffHunkIndices.add(node.getIndex());
        diffHunkIDs.add(node.getUUID());

        // update stats
        indexToGroupMap.put(node.getIndex(), groupID);
      }
      Group group = new Group(repoID, repoName, groupID, diffHunkIndices, diffHunkIDs, intent);
      group.setCommitMsg(intent.toString().toLowerCase() + ": " + intent.label + " ...");

      group.addLinkCategories(linkCategories);

      // add to result groups
      groups.put(groupID, group);

      return group.getGroupID();
    }
    return "";
  }

  /**
   * Merge source group into target group, return the id of merged group
   *
   * @return
   */
  private String mergeGroups(Map<String, Group> groups, Group g1, Group g2) {
    for (String id : g1.getDiffHunkIDs()) {
      g2.addByID(id);
    }

    for (String index : g1.getDiffHunkIndices()) {
      g2.addByIndex(index);
      indexToGroupMap.replace(index, g2.getGroupID());
    }

    g2.addLinkCategories(g1.getLinkCategories());

    // remove g1
    groups.remove(g1.getGroupID());
    return g2.getGroupID();
  }

  /**
   * Add the node to the group
   *
   * @param node
   * @param group
   */
  private String addToGroup(DiffNode node, Group group) {
    group.addByID(node.getUUID());
    group.addByIndex(node.getIndex());
    if (indexToGroupMap.containsKey(node.getIndex())) {
      indexToGroupMap.replace(node.getIndex(), group.getGroupID());
    } else {
      indexToGroupMap.put(node.getIndex(), group.getGroupID());
    }
    return group.getGroupID();
  }

  /**
   * Add an individual diff hunk to its nearest group
   *
   * @param groups
   * @param individuals
   */
  private void assignIndividuals(Map<String, Group> groups, Set<DiffNode> individuals) {
    Set<DiffNode> temp = new HashSet<>(individuals);
    for (DiffNode node : temp) {
      // find the group of the nearest diff hunk
      // after sibling
      String after = node.getFileIndex() + ":" + (node.getDiffHunkIndex() + 1);
      if (this.indexToGroupMap.containsKey(after)) {
        Group group = groups.get(this.indexToGroupMap.get(after));
        group.addByID(node.getUUID());
        this.indexToGroupMap.put(node.getIndex(), group.getGroupID());
        individuals.remove(node);
        continue;
      }
      // before sibling
      String before = node.getFileIndex() + ":" + (node.getDiffHunkIndex() - 1);
      if (this.indexToGroupMap.containsKey(before)) {
        Group group = groups.get(this.indexToGroupMap.get(before));
        group.addByID(node.getUUID());
        this.indexToGroupMap.put(node.getIndex(), group.getGroupID());
        individuals.remove(node);
        continue;
      }
      // same parent file
      String parent = node.getFileIndex() + ":0";
      if (this.indexToGroupMap.containsKey(parent)) {
        Group group = groups.get(this.indexToGroupMap.get(parent));
        group.addByID(node.getUUID());
        this.indexToGroupMap.put(node.getIndex(), group.getGroupID());
        individuals.remove(node);
        continue;
      }
    }
  }

  /**
   * Add general nodes diff hunk to groups
   *
   * @param groups
   * @param generalNodes
   */
  private void assignGeneralNodes(Map<String, Group> groups, Set<String> generalNodes, Set<Integer> weakDependEdges){
    for(String node: generalNodes){
      for(DiffEdge edge : diffGraph.edgeSet()){
        if(weakDependEdges.contains(edge.getId())){
          DiffNode target = diffGraph.getEdgeTarget(edge);

          if(target.getIndex().equals(node) && !isRealAddHunk(getDiffHunkByIndex(target.getIndex()))){
            DiffNode source = diffGraph.getEdgeSource(edge);
            Group group = groups.get(this.indexToGroupMap.get(source.getIndex()));
            group.addByID(target.getUUID());
            this.indexToGroupMap.put(node, this.indexToGroupMap.getOrDefault(node, "") + ", " + group.getGroupID());
          }
        }
      }
    }
  }

  private DiffHunk getDiffHunkByIndex(String index) {
    for (DiffHunk diffHunk : diffHunks) {
      if (diffHunk.getUniqueIndex().equals(index)) {
        return diffHunk;
      }
    }
    return null;
  }

  private static void removeEntriesRelatedToDiffHunks(Map<String, Set<String>> hardLinks, Set<DiffHunk>... diffHunks) {
    Set<String> keysToRemove = new HashSet<>();
    for (Set<DiffHunk> diffHunkSet : diffHunks) {
      for (DiffHunk diffHunk : diffHunkSet) {
        String uniqueIndex = diffHunk.getUniqueIndex();
        keysToRemove.add(uniqueIndex);
        hardLinks.values().removeIf(value -> value.contains(uniqueIndex));
      }
    }
    // 移除涉及的键
    keysToRemove.forEach(hardLinks::remove);
  }

  private Set<String> getGeneralNodes(Map<String, Set<String>> hardLinks){
    Set<String> generalNodes = new HashSet<>();
    Map<String, Set<String>> stringCounts = new HashMap<>();
    // 统计每个字符串出现的次数
    for(Map.Entry<String, Set<String>> entry: hardLinks.entrySet()){
      String key = entry.getKey();
      for(String value: entry.getValue()){
        if(!value.equals(key)){
          // 如果在 stringCounts 中存在该 key，则将 entry.getKey() 添加到对应的集合中
          if (stringCounts.containsKey(value)) {
            stringCounts.get(value).add(key);
          } else {
            // 如果不存在该 key，则创建一个新的 HashSet，并将 entry.getKey() 添加到其中
            Set<String> set = new HashSet<>();
            set.add(key);
            stringCounts.put(value, set);
          }
        }
      }
    }
    // 找到出现次数大于等于三次且不是键的字符串
    for (Map.Entry<String,  Set<String>> entry : stringCounts.entrySet()) {
      if (entry.getValue().size() >= 3 && (!hardLinks.containsKey(entry.getKey()) || (hardLinks.get(entry.getKey()).size() == 1 && hardLinks.get(entry.getKey()).contains(entry.getKey())))) {
        generalNodes.add(entry.getKey());
      }
    }
    return generalNodes;
  }

  /**
   * source --> target in order
   *
   * @param graph
   * @return
   */
  private Map<String, Set<String>> analyzeHardLinks(Graph<Node, Edge> graph) {
    Map<String, Set<String>> results = new HashMap<>();
    List<Node> diffEntities =
        graph.vertexSet().stream().filter(node -> node.isInDiffHunk).collect(Collectors.toList());
    for (Node node : diffEntities) {
      List<Node> defs =
          Graphs.predecessorListOf(graph, node).stream()
              .filter(n -> n.isInDiffHunk)
              .collect(Collectors.toList());
      if (!defs.isEmpty()) {
        for (Node u : defs) {
          if (u.getDiffHunkIndex().equals(node.getDiffHunkIndex())) {
            continue;
          } else if (!results.containsKey(node.diffHunkIndex)) {
            results.put(node.diffHunkIndex, new HashSet<>());
          }
          results.get(node.diffHunkIndex).add(u.getDiffHunkIndex());
        }
      }
      // direct one-hop dependency
      List<Node> uses =
          Graphs.successorListOf(graph, node).stream()
              .filter(n -> n.isInDiffHunk)
              .collect(Collectors.toList());
      if (!uses.isEmpty()) {
        for (Node u : uses) {
          if (u.getDiffHunkIndex().equals(node.getDiffHunkIndex())) {
            continue;
          } else if (!results.containsKey(node.diffHunkIndex)) {
            results.put(node.diffHunkIndex, new HashSet<>());
          }
          results.get(node.diffHunkIndex).add(u.getDiffHunkIndex());
        }
      }
    }
    return results;
  }

  /**
   * Build edges in the diff hunk graph for groups
   *
   * @param diffHunks
   * @param type
   */
  private void createEdges(Set<DiffHunk> diffHunks, DiffEdgeType type, double weight) {
    if (diffHunks.isEmpty()) {
      return;
    }

    List<DiffHunk> list = new ArrayList<>(diffHunks);
    if (diffHunks.size() == 1) {
      createEdge(list.get(0).getUniqueIndex(), list.get(0).getUniqueIndex(), type, weight);
    }
    // create groups and build edges in order
    for (int i = 0; i < list.size() - 1; i++) {
      if (i + 1 < list.size()) {
        createEdge(list.get(i).getUniqueIndex(), list.get(i + 1).getUniqueIndex(), type, weight);
      }
    }
  }

  /**
   * Create one edge in the diff graph
   *
   * @param source
   * @param target
   * @param type
   * @param weight
   */
  private void createEdge(String source, String target, DiffEdgeType type, double weight) {
    boolean success =
        diffGraph.addEdge(
            findNodeByIndex(source),
            findNodeByIndex(target),
            new DiffEdge(generateEdgeID(), type, weight));
    if (!success) {
      // in case of failure
      logger.error("Error when adding edge: " + source + "->" + target + "to diffGraph.");
    }
  }

  private Integer generateEdgeID() {
    return this.diffGraph.edgeSet().size() + 1;
  }

  private DiffNode findNodeByIndex(String index) {
    Optional<DiffNode> nodeOpt =
        diffGraph.vertexSet().stream()
            .filter(diffNode -> diffNode.getIndex().equals(index))
            .findAny();
    return nodeOpt.orElse(null);
  }

  private Set<DiffHunk> detectRefactorings(Set<DiffHunk> refDiffHunks) {
    //    Set<DiffHunk> refDiffHunks = new TreeSet<>(ascendingByIndexComparator());

    try {
      File rootFolder1 = new File(srcDirs.getLeft());
      File rootFolder2 = new File(srcDirs.getRight());

      UMLModel model1 = new UMLModelASTReader(rootFolder1).getUmlModel();
      UMLModel model2 = new UMLModelASTReader(rootFolder2).getUmlModel();
      UMLModelDiff modelDiff = model1.diff(model2);

      List<Refactoring> refactorings = modelDiff.getRefactorings();

      // for each refactoring, find the corresponding diff hunk
      for (Refactoring refactoring : refactorings) {
        // greedy style: put all refactorings into one group
        for (CodeRange range : refactoring.leftSide()) {
          Set<DiffHunk> diffHunkOpt = getOverlappingDiffHunk(Version.BASE, range);
          if (diffHunkOpt.size() > 0) {
            for(DiffHunk diffHunk : diffHunkOpt) {
              diffHunk.addRefAction(Utils.convertRefactoringToAction(refactoring));
              refDiffHunks.add(diffHunk);
            }
          }
        }
        for (CodeRange range : refactoring.rightSide()) {
          Set<DiffHunk> diffHunkOpt = getOverlappingDiffHunk(Version.CURRENT, range);
          if (diffHunkOpt.size() > 0) {
            for(DiffHunk diffHunk : diffHunkOpt) {
              diffHunk.addRefAction(Utils.convertRefactoringToAction(refactoring));
              refDiffHunks.add(diffHunk);
            }
          }
        }
      }
    } catch (RefactoringMinerTimedOutException | IOException e) {
      e.printStackTrace();
    }
    return refDiffHunks;
  }

//  private Map<Integer, Set<DiffHunk>>  detectRefactorings(Map<Integer, Set<DiffHunk>>  refDiffHunks) {
//    //    Set<DiffHunk> refDiffHunks = new TreeSet<>(ascendingByIndexComparator());
//    Set<DiffHunk> diffHunkSet = new TreeSet<>(diffHunkComparator()); // 使用比较器初始化 TreeSet
//
//    try {
//      File rootFolder1 = new File(srcDirs.getLeft());
//      File rootFolder2 = new File(srcDirs.getRight());
//
//      UMLModel model1 = new UMLModelASTReader(rootFolder1).getUmlModel();
//      UMLModel model2 = new UMLModelASTReader(rootFolder2).getUmlModel();
//      UMLModelDiff modelDiff = model1.diff(model2);
//
//      List<Refactoring> refactorings = modelDiff.getRefactorings();
//
//      // for each refactoring, find the corresponding diff hunk
//      for (Refactoring refactoring : refactorings) {
//        // greedy style: put all refactorings into one group
//        for (CodeRange range : refactoring.leftSide()) {
//          Optional<DiffHunk> diffHunkOpt = getOverlappingDiffHunk(Version.BASE, range);
//          if (diffHunkOpt.isPresent()) {
//            DiffHunk diffHunk = diffHunkOpt.get();
//            diffHunk.addRefAction(Utils.convertRefactoringToAction(refactoring));
//            diffHunkSet.add(diffHunk);
//          }
//        }
//        for (CodeRange range : refactoring.rightSide()) {
//          Optional<DiffHunk> diffHunkOpt = getOverlappingDiffHunk(Version.CURRENT, range);
//          if (diffHunkOpt.isPresent()) {
//            DiffHunk diffHunk = diffHunkOpt.get();
//            diffHunk.addRefAction(Utils.convertRefactoringToAction(refactoring));
//            diffHunkSet.add(diffHunk);
//          }
//        }
//      }
//      // 将相似度接近的 diffHunk 放到同一个 set 中
//        for (DiffHunk diffHunk : diffHunkSet) {
//          for(DiffHunk diffHunk1 : diffHunkSet){
//            if(diffHunk != diffHunk1){
//              double similarity = detectCrossVersionSimilarity(diffHunk, diffHunk1);
//              if(similarity > 0.8){
//                diffHunk.addSimilarDiffHunk(diffHunk1);
//              }
//            }
//          }
//        }
//
//    } catch (RefactoringMinerTimedOutException | IOException e) {
//      e.printStackTrace();
//    }
//    return refDiffHunks;
//  }

  /**
   * Construct a comparator which rank the diffhunks firstly by fileIndex, secondly by diffHunkIndex
   *
   * @return
   */
  private Comparator<DiffHunk> diffHunkComparator() {
    return Comparator.comparing(DiffHunk::getFileIndex).thenComparing(DiffHunk::getIndex);
  }

  private Comparator<DiffNode> diffNodeComparator() {
    return Comparator.comparing(DiffNode::getFileIndex).thenComparing(DiffNode::getDiffHunkIndex);
  }

  /**
   * Compute textual similarity (and tree similarity) between two diff hunks
   *
   * @param diffHunk
   * @param diffHunk1
   * @return
   */
  private double detectSimilarity(DiffHunk diffHunk, DiffHunk diffHunk1) {
    // ignore special cases: imports, empty, blank_lines
    if (diffHunk.getFileType().equals(FileType.JAVA)
        && diffHunk1.getFileType().equals(FileType.JAVA)) {
      if(diffHunk.getDescription().contains("\"") && Objects.equals(diffHunk.getDescription(), diffHunk1.getDescription())){
        return 1.1D;
      }

      // textual similarity
      CodeSimilarity cosineSimilarity = new CodeSimilarity(new CosineSimilarity());
      CodeSimilarity codeSimilarity = new CodeSimilarity();
      double baseText = cosineSimilarity.get(
              Utils.convertListLinesToString(diffHunk.getBaseHunk().getCodeSnippet()),
              Utils.convertListLinesToString(diffHunk1.getBaseHunk().getCodeSnippet()));
      double baseText2 = codeSimilarity.get(
              Utils.convertListLinesToString(diffHunk.getBaseHunk().getCodeSnippet()),
              Utils.convertListLinesToString(diffHunk1.getBaseHunk().getCodeSnippet()));
      double currentText = cosineSimilarity.get(
              Utils.convertListLinesToString(diffHunk.getCurrentHunk().getCodeSnippet()),
              Utils.convertListLinesToString(diffHunk1.getCurrentHunk().getCodeSnippet()));
      double currentText2 = codeSimilarity.get(
              Utils.convertListLinesToString(diffHunk.getCurrentHunk().getCodeSnippet()),
              Utils.convertListLinesToString(diffHunk1.getCurrentHunk().getCodeSnippet()));
      // change action similarity
//        double astSimi =
//            Utils.computeListSimilarity(diffHunk.getAstActions(), diffHunk1.getAstActions());
//        double refSimi =
//            Utils.computeListSimilarity(diffHunk.getRefActions(), diffHunk1.getRefActions());
//        return Utils.formatDouble((baseText + currentText) / 2);
      if(diffHunk.getChangeType().equals(ChangeType.ADDED) || diffHunk1.getChangeType().equals(ChangeType.ADDED))
        return Utils.formatDouble((currentText + currentText2) / 2);
      else if(diffHunk.getChangeType().equals(ChangeType.DELETED) || diffHunk1.getChangeType().equals(ChangeType.DELETED))
        return Utils.formatDouble((baseText + baseText2) / 2);
      else
        return Utils.formatDouble((baseText + currentText + baseText2 + currentText2 ) / 4);

    }
    return 0D;
  }

  private double detectCrossVersionSimilarity(DiffHunk diffHunk, DiffHunk diffHunk1) {
    // TODO: use token similarity instead
    String left =
        Utils.convertListLinesToString(
            diffHunk.getChangeType().equals(ChangeType.ADDED)
                ? diffHunk.getCurrentHunk().getCodeSnippet()
                : diffHunk.getBaseHunk().getCodeSnippet());
    String right =
        Utils.convertListLinesToString(
            diffHunk1.getChangeType().equals(ChangeType.ADDED)
                ? diffHunk1.getCurrentHunk().getCodeSnippet()
                : diffHunk1.getBaseHunk().getCodeSnippet());
    CodeSimilarity cosineSimilarity = new CodeSimilarity(new CosineSimilarity());
    CodeSimilarity codeSimilarity = new CodeSimilarity();
    return Utils.formatDouble((cosineSimilarity.get(left, right) + codeSimilarity.get(left, right)) / 2);
  }

  /**
   * Estimate the location distance of two diff hunks, from both base and current
   *
   * @param diffHunk
   * @param diffHunk1
   * @return
   */
  private int detectProxmity(DiffHunk diffHunk, DiffHunk diffHunk1) {
    int distance = -1; // -1 means no way to compute distance
    if (diffHunk.getFileType().equals(FileType.JAVA)
        && diffHunk1.getFileType().equals(FileType.JAVA)) {
      Optional<DiffNode> diffNodeOpt1 =
          diffGraph.vertexSet().stream()
              .filter(node -> node.getIndex().equals(diffHunk.getUniqueIndex()))
              .findAny();
      Optional<DiffNode> diffNodeOpt2 =
          diffGraph.vertexSet().stream()
              .filter(node -> node.getIndex().equals(diffHunk1.getUniqueIndex()))
              .findAny();
      if (diffNodeOpt1.isPresent() && diffNodeOpt2.isPresent()) {
        DiffNode diffNode1 = diffNodeOpt1.get();
        DiffNode diffNode2 = diffNodeOpt2.get();
        int disBase = -1;
        int disCurrent = -1;
        if (!diffNode1.getBaseHierarchy().isEmpty() && !diffNode2.getBaseHierarchy().isEmpty()) {
          disBase = compareHierarchy(diffNode1.getBaseHierarchy(), diffNode2.getBaseHierarchy());
        }
        if (!diffNode1.getCurrentHierarchy().isEmpty()
            && !diffNode2.getCurrentHierarchy().isEmpty()) {
          disCurrent =
              compareHierarchy(diffNode1.getCurrentHierarchy(), diffNode2.getCurrentHierarchy());
        }
        // use the min distance
        if (disBase < 0) {
          distance = disCurrent;
        } else if (disCurrent < 0) {
          distance = disBase;
        } else {
          distance = Math.min(disBase, disCurrent);
        }
      }
    }
    return distance;
  }

  /**
   * Check if the given two diff hunks are in a pair of tested class and test case by file path
   *
   * @param diffHunk
   * @param diffHunk1
   * @return <source (test file), target>
   */
  private Pair<String, String> detectTesting(DiffHunk diffHunk, DiffHunk diffHunk1) {
    String leftPath =
        Utils.getFileNameFromPath(
                diffHunk.getChangeType().equals(ChangeType.ADDED)
                    ? diffHunk.getCurrentHunk().getRelativeFilePath()
                    : diffHunk.getBaseHunk().getRelativeFilePath())
            .replace(".java", "");
    String rightPath =
        Utils.getFileNameFromPath(
                diffHunk1.getChangeType().equals(ChangeType.DELETED)
                    ? diffHunk1.getBaseHunk().getRelativeFilePath()
                    : diffHunk1.getCurrentHunk().getRelativeFilePath())
            .replace(".java", "");
    if (rightPath.equals(leftPath + "Test")) {
      // right test left
      return Pair.of(diffHunk1.getUniqueIndex(), diffHunk.getUniqueIndex());
    } else if (leftPath.equals(rightPath + "Test")) {
      return Pair.of(diffHunk.getUniqueIndex(), diffHunk1.getUniqueIndex());
    }
    return null;
  }

  /**
   * Compare hierarchy to compute the location distance
   *
   * @return
   */
  private int compareHierarchy(Map<String, Integer> hier1, Map<String, Integer> hier2) {
    // TODO use index to estimate distance
    if (hier1.isEmpty() || hier2.isEmpty()) {
      return -1;
    }
    int res = 4;
    for (Map.Entry<String, Integer> entry : hier1.entrySet()) {
      if (hier2.containsKey(entry.getKey())) {
        if (hier2.get(entry.getKey()).equals(entry.getValue())) {
          int t = -1;
          switch (entry.getKey()) {
            case "hunk":
              t = 0;
              break;
            case "member":
              t = 1;
              break;
            case "class":
              t = 2;
              break;
            case "package":
              t = 3;
              break;
          }
          res = Math.min(res, t);
        }
      }
    }
    return res;
  }

  /**
   * Get the hierarchy for hunk nodes Hierarchy is a map of up-to-top parent ids
   *
   * @param graph
   * @param nodes
   * @param diffHunkIndex
   * @return
   */
  private Map<String, Integer> getHierarchy(
      Graph<Node, Edge> graph, List<Node> nodes, String diffHunkIndex) {
    Map<String, Integer> hierarchy = new HashMap<>();
    Optional<Node> nodeOpt =
        nodes.stream().filter(node -> node.getDiffHunkIndex().equals(diffHunkIndex)).findAny();
    if (nodeOpt.isPresent()) {
      Node node = nodeOpt.get();
      hierarchy.put("hunk", node.getId());
      // find parents from incoming edges
      findAncestors(graph, node, hierarchy);
    }
    return hierarchy;
  }

  /**
   * Find the hierarchical definition ancestors (memeber, class, package)
   *
   * @param graph
   * @param node
   * @param hierarchy
   */
  private void findAncestors(Graph<Node, Edge> graph, Node node, Map<String, Integer> hierarchy) {
    Set<Edge> incomingEdges =
        graph.incomingEdgesOf(node).stream()
            .filter(edge -> edge.getType().isStructural()) // contain or define
            .collect(Collectors.toSet());
    for (Edge edge : incomingEdges) {
      Node srcNode = graph.getEdgeSource(edge);
      switch (srcNode.getType()) {
        case CLASS:
        case INTERFACE:
        case ENUM:
        case ANNOTATION:
          hierarchy.put("class", srcNode.getId());
          findAncestors(graph, srcNode, hierarchy);
          break;
        case METHOD:
        case FIELD:
        case ENUM_CONSTANT:
        case ANNOTATION_MEMBER:
        case INITIALIZER_BLOCK:
          hierarchy.put("member", srcNode.getId());
          findAncestors(graph, srcNode, hierarchy);
          break;
        case PACKAGE:
          hierarchy.put("package", srcNode.getId());
          break;
      }
    }
  }

  /**
   * Try to find the overlapping diff hunk according to the code range
   *
   * @return
   */
//  private Optional<DiffHunk> getOverlappingDiffHunk(Version version, CodeRange codeRange) {
//    for (DiffFile diffFile : diffFiles) {
//      if (!codeRange.getFilePath().isEmpty()
//          && !diffFile.getRelativePathOf(version).isEmpty()
//          && codeRange.getFilePath().endsWith(diffFile.getRelativePathOf(version))) {
//        for (DiffHunk diffHunk : diffFile.getDiffHunks()) {
//          Pair<Integer, Integer> hunkRange = diffHunk.getCodeRangeOf(version);
//          // overlapping: !(b1 < a2 || b2 < a1) = (b1 >= a2 && b2 >= a1)
//          if (codeRange.getEndLine() >= hunkRange.getLeft()
//              && hunkRange.getRight() >= codeRange.getStartLine()) {
//            // suppose that one range is related with only one diff hunk
//            //todo wrong suppose
//            return Optional.of(diffHunk);
//          }
//        }
//      }
//    }
//    return Optional.empty();
//  }

  private Set<DiffHunk> getOverlappingDiffHunk(Version version, CodeRange codeRange) {
    Set<DiffHunk> refDiffHunks = new TreeSet<>(diffHunkComparator());
    for (DiffFile diffFile : diffFiles) {
      if (!codeRange.getFilePath().isEmpty()
              && !diffFile.getRelativePathOf(version).isEmpty()
              && codeRange.getFilePath().endsWith(diffFile.getRelativePathOf(version))) {
        for (DiffHunk diffHunk : diffFile.getDiffHunks()) {
          Pair<Integer, Integer> hunkRange = diffHunk.getCodeRangeOf(version);
          // overlapping: !(b1 < a2 || b2 < a1) = (b1 >= a2 && b2 >= a1)
          if (codeRange.getEndLine() >= hunkRange.getLeft()
                  && hunkRange.getRight() >= codeRange.getStartLine()) {
            refDiffHunks.add(diffHunk);
          }
        }
      }
    }
    return refDiffHunks;
  }

  /**
   * Check if a diff hunk only contains reformatting changes with whitespace, indentation,
   * punctuation, etc.
   *
   * @param diffHunk
   * @return
   */
//  public boolean detectReformatting(DiffHunk diffHunk) {
//    String baseString = Utils.convertListToStringNoFormat(diffHunk.getBaseHunk().getCodeSnippet());
//    String currentString = Utils.convertListToStringNoFormat(diffHunk.getCurrentHunk().getCodeSnippet());
//    return baseString.equalsIgnoreCase((currentString));
//  }
  public boolean detectReformatting(DiffHunk diffHunk) {
    ContentType baseContentType = diffHunk.getBaseHunk().getContentType();
    ContentType currentContentType = diffHunk.getCurrentHunk().getContentType();
    if((isSpecialContentType(baseContentType) && isSpecialContentType(currentContentType))){
      return true;
    }
    String baseString = removeComments(Utils.convertListLinesToString(diffHunk.getBaseHunk().getCodeSnippet()));
    String currentString = removeComments(Utils.convertListLinesToString(diffHunk.getCurrentHunk().getCodeSnippet()));
    return baseString.equalsIgnoreCase((currentString));
  }

  // Function to check if the content type belongs to a specific set of types
  private boolean isSpecialContentType(ContentType contentType) {
    return contentType.equals(ContentType.COMMENT) ||
            contentType.equals(ContentType.BLANKLINE) ||
            contentType.equals(ContentType.EMPTY);
  }

  private boolean isRealAddHunk(DiffHunk diffHunk){
    if(diffHunk.getChangeType() == ChangeType.ADDED){
      return true;
    }
    else if(diffHunk.getChangeType() == ChangeType.MODIFIED && isSpecialContentType(diffHunk.getBaseHunk().getContentType())){
      return true;
    }
    return false;
  }

  public static boolean detectReformatting(String baseString, String currentString) {
    return removeComments(baseString).equalsIgnoreCase(removeComments(currentString));
  }

  public static String removeComments(String code) {
//    return code.replaceAll("//.*|/\\*(?:.|[\\n\\r])*?\\*/", "")
//    return code.replaceAll("//.*|/\\*(.|\\R)*?\\*/", "")
    return code.replaceAll("//.*|/\\*[^*]*\\*+([^*/][^*]*\\*+)*/", "")
            .replaceAll("\\\\t|[\\\\r?\\\\n]+|\\s+", "");
  }

  private boolean detectTest(DiffHunk diffHunk) {
    if((diffHunk.getBaseHunk().getRelativeFilePath().contains("test/") || diffHunk.getCurrentHunk().getRelativeFilePath().contains("test/"))) {
      return true;
    }
    return false;
  }

  private boolean detectMove(DiffHunk diffHunk, DiffHunk diffHunk1) {
    List<Action> astAction = diffHunk.getAstActions();
    List<Action> astAction1 = diffHunk1.getAstActions();

    for (Action action : astAction) {
      if (action.getOperation() == Operation.ADD) {
        for (Action action1 : astAction1) {
          if (action1.getOperation() == Operation.DEL && action.toString().contains("\"") &&
                  action.toString().replace("Add", "").equals(action1.toString().replace("Delete", ""))) {
            return true;
          }
        }
      } else if (action.getOperation() == Operation.DEL) {
        for (Action action1 : astAction1) {
          if (action1.getOperation() == Operation.ADD && action.toString().contains("\"") &&
                  action.toString().replace("Delete", "").equals(action1.toString().replace("Add", ""))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public void enableRefDetection(boolean enable) {
    this.detectRefs = enable;
  }

  public void enableNonJavaChanges(boolean process) {
    this.processNonJava = process;
  }

  public void setMinSimilarity(double minSimilarity) {
    this.minSimilarity = minSimilarity;
  }

  public void setMaxDistance(int maxDistance) {
    this.maxDistance = maxDistance;
  }

  public List<Action> detectRefAction(DiffHunk diffHunk, DiffHunk diffHunk1){
    List<Action> res = new ArrayList<>();
    List<Action> list1 = diffHunk.getRefActions();
    List<Action> list2 = diffHunk1.getRefActions();
    for (Action action1 : list1) {
      for (Action action2 : list2) {
        if (action1.equals(action2)) {
          res.add(action1);
        }
      }
    }
    return res;
  }
}
