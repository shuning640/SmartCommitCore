package com.github.smartcommit.client;

import com.github.smartcommit.core.GraphBuilder;
import com.github.smartcommit.core.GroupGenerator;
import com.github.smartcommit.core.RepoAnalyzer;
import com.github.smartcommit.core.dd.MysqlManager;
import com.github.smartcommit.core.dd.Revert;
import com.github.smartcommit.io.DataCollector;
import com.github.smartcommit.model.*;
import com.github.smartcommit.model.constant.ChangeType;
import com.github.smartcommit.model.constant.GroupLabel;
import com.github.smartcommit.model.constant.Version;
import com.github.smartcommit.model.graph.Edge;
import com.github.smartcommit.model.graph.Node;
import com.github.smartcommit.util.Executor;
import com.github.smartcommit.util.GitServiceCGit;
import com.github.smartcommit.util.Utils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import org.eclipse.jdt.core.dom.*;
import org.jgrapht.Graph;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/** API entry */
public class SmartCommit {
  private static final Logger logger = Logger.getLogger(SmartCommit.class);
  private final String repoID;
  private final String repoName;
  private final String repoPath;
  private final String tempDir;
  private Map<String, DiffHunk> id2DiffHunkMap;

  // saved for analysis
  Graph<Node, Edge> baseGraph;
  Graph<Node, Edge> currentGraph;

  // options and default
  private boolean detectRefactorings = true;
  private boolean processNonJavaChanges = true;
  private double weightThreshold = 0.8D;
  private double minSimilarity = 0.8D;
  private int maxDistance = 1;

  static GroupGenerator generator = new GroupGenerator();

  /**
   * Initial setup for analysis
   *
   * @param repoID repo unique id (used for database)
   * @param repoName repo name
   * @param repoPath absolute local repo path
   * @param tempDir temporary directory path for intermediate and final result
   */
  public SmartCommit(String repoID, String repoName, String repoPath, String tempDir) {
    this.repoID = repoID;
    this.repoName = repoName;
    this.repoPath = repoPath;
    this.tempDir = tempDir;
    this.baseGraph = null;
    this.currentGraph = null;
    this.id2DiffHunkMap = new HashMap<>();
  }

  public void setDetectRefactorings(boolean detectRefactorings) {
    this.detectRefactorings = detectRefactorings;
  }

  public void setProcessNonJavaChanges(boolean processNonJavaChanges) {
    this.processNonJavaChanges = processNonJavaChanges;
  }

  public void setWeightThreshold(Double weightThreshold) {
    this.weightThreshold = weightThreshold;
  }

  public void setMinSimilarity(double minSimilarity) {
    this.minSimilarity = minSimilarity;
  }

  public void setMaxDistance(int maxDistance) {
    this.maxDistance = maxDistance;
  }

  public void setId2DiffHunkMap(Map<String, DiffHunk> id2DiffHunkMap) {
    this.id2DiffHunkMap = id2DiffHunkMap;
  }

  public Map<String, DiffHunk> getId2DiffHunkMap() {
    return id2DiffHunkMap;
  }

  /**
   * Clear the temp dir and create the logs dir
   *
   */
  private void prepareTempDir(String dir) {
    Utils.clearDir(dir);
    System.setProperty("logs.dir", dir);
    //    PropertyConfigurator.configure("log4j.properties");
  }

  /**
   * Analyze the current working directory of the repository
   *
   * @return suggested groups <id:group>
   */
  public Map<String, Group> analyzeWorkingTree() throws Exception{
    prepareTempDir(tempDir);
    // 1. analyze the repo
    RepoAnalyzer repoAnalyzer = new RepoAnalyzer(repoID, repoName, repoPath);
    List<DiffFile> diffFiles = repoAnalyzer.analyzeWorkingTree();
    List<DiffHunk> allDiffHunks = repoAnalyzer.getDiffHunks();
    if (diffFiles.isEmpty()) {
      logger.info("Nothing to commit, working tree clean.");
      return new HashMap<>();
    }

    boolean onlyEncodingChange = false;
    if (allDiffHunks.isEmpty()) {
      logger.info("Changes exist, but not in file contents.");
      onlyEncodingChange = true;

      // mock one dummy diff hunk for each diff file to continue commit
      for (DiffFile diffFile : diffFiles) {
        List<DiffHunk> diffHunksInFile = new ArrayList<>();

        DiffHunk diffHunk =
            new DiffHunk(
                0,
                diffFile.getFileType(),
                ChangeType.MODIFIED,
                new com.github.smartcommit.model.Hunk(
                    Version.BASE,
                    diffFile.getBaseRelativePath(),
                    0,
                    0,
                    Utils.checkContentType(Utils.convertStringToList(diffFile.getBaseContent())),
                    new ArrayList<>()),
                new com.github.smartcommit.model.Hunk(
                    Version.CURRENT,
                    diffFile.getCurrentRelativePath(),
                    0,
                    0,
                    Utils.checkContentType(Utils.convertStringToList(diffFile.getCurrentContent())),
                    new ArrayList<>()),
                "file/line encodings change");
        diffHunk.setRawDiffs(new ArrayList<>());
        // bind diff file with diff hunks
        diffHunk.setFileIndex(diffFile.getIndex());
        diffHunksInFile.add(diffHunk);
        diffFile.setDiffHunks(diffHunksInFile);

        allDiffHunks.add(diffHunk);
      }
    }

    this.id2DiffHunkMap = repoAnalyzer.getIdToDiffHunkMap();

    // 2. collect the data into temp dir
    // (1) diff files (2) file id mapping (3) diff hunks
    DataCollector dataCollector = new DataCollector(repoName, tempDir);
    // dirs that keeps the source code of diff files
    Pair<String, String> srcDirs = dataCollector.collectDiffFilesWorking(diffFiles);

    Map<String, Group> results = new HashMap<>();
    if (onlyEncodingChange) {
      List<String> allDiffHunkIDs =
          allDiffHunks.stream().map(DiffHunk::getDiffHunkID).collect(Collectors.toList());
      Group group = new Group(repoID, repoName, "group0", allDiffHunkIDs, GroupLabel.OTHER);
      group.setRecommendedCommitMsgs(Collections.singletonList("Change file/line encodings."));
      results.put("group0", group);
    } else {
      results = analyze(diffFiles, allDiffHunks, srcDirs);
    }

    dataCollector.collectDiffHunks(diffFiles, tempDir);

    // generate commit message
    if (results != null) {
      for (Map.Entry<String, Group> entry : results.entrySet()) {
        Group group = entry.getValue();
        // generate recommended commit messages
        group.setRecommendedCommitMsgs(generateCommitMsg(group));
      }
      // save the results on disk
      exportGroupResults(results, tempDir);
      exportGroupDetails(results, tempDir + File.separator + "details");
    }

    return results;
  }

  /**
   * Analyze a specific commit of the repository for decomposition
   *
   * @param commitID the target commit hash id
   * @return suggested groups <id:group>
   */
  public Map<String, Group> analyzeCommit(String commitID) throws Exception{
    String resultsDir = tempDir + File.separator + commitID;
    prepareTempDir(resultsDir);

    // 1. analyze the repo
    RepoAnalyzer repoAnalyzer = new RepoAnalyzer(repoID, repoName, repoPath);
    List<DiffFile> diffFiles = repoAnalyzer.analyzeCommit(commitID);
    if(diffFiles.size() > 100){
        logger.info("Too many files to analyze at commit: " + commitID);
        return new HashMap<>();
    }
    List<DiffHunk> allDiffHunks = repoAnalyzer.getDiffHunks();

    if (diffFiles.isEmpty() || allDiffHunks.isEmpty()) {
      logger.info("No changes at commit: " + commitID);
      return new HashMap<>();
    }

    this.id2DiffHunkMap = repoAnalyzer.getIdToDiffHunkMap();

    // 2. collect the data into temp dir
    DataCollector dataCollector = new DataCollector(repoName, tempDir);
    // dirs that keeps the source code of diff files
    Pair<String, String> srcDirs = dataCollector.collectDiffFilesAtCommit(commitID, diffFiles);

    Map<String, Group> results = analyze(diffFiles, allDiffHunks, srcDirs);

    dataCollector.collectDiffHunks(diffFiles, resultsDir);

    exportGroupResults(results, resultsDir);
    exportGroupDetails(results, resultsDir + File.separator + "details");

    return results;
  }

  public Map<String, Group> analyzeCommit(String OldCommitID, String commitID) throws Exception{
    String resultsDir = tempDir + File.separator + commitID;
    prepareTempDir(resultsDir);

    // 1. analyze the repo
    RepoAnalyzer repoAnalyzer = new RepoAnalyzer(repoID, repoName, repoPath);
    List<DiffFile> diffFiles = repoAnalyzer.analyzeCommit(OldCommitID, commitID);
    if(diffFiles.size() > 100){
      logger.info("Too many files to analyze at commit: " + commitID);
      return new HashMap<>();
    }
    List<DiffHunk> allDiffHunks = repoAnalyzer.getDiffHunks();

    if (diffFiles.isEmpty() || allDiffHunks.isEmpty()) {
      logger.info("No changes at commit: " + commitID);
      return new HashMap<>();
    }

    this.id2DiffHunkMap = repoAnalyzer.getIdToDiffHunkMap();

    // 2. collect the data into temp dir
    DataCollector dataCollector = new DataCollector(repoName, tempDir);
    // dirs that keeps the source code of diff files
    Pair<String, String> srcDirs = dataCollector.collectDiffFilesAtCommit(commitID, diffFiles);

    Map<String, Group> results = analyze(diffFiles, allDiffHunks, srcDirs);

    Utils.writeStringToFile(generator.buildGroupsGraph(results), resultsDir + File.separator + "groupGraph.dot");

    dataCollector.collectDiffHunks(diffFiles, resultsDir);

    exportGroupResults(results, resultsDir);
    exportGroupDetails(results, resultsDir + File.separator + "details");

    return results;
  }

//  public List<DiffHunk> group2Hunks(Group group){
//    List<DiffHunk> hunks = new ArrayList<>();
//    for (String diffHunkID : group.getDiffHunkIDs()) {
//      DiffHunk diffHunk = id2DiffHunkMap.get(diffHunkID.split(":")[1]);
//      if (diffHunk != null) {
//        HunkEntity hunkEntity = new HunkEntity();
//        hunkEntity.setType(diffHunk.getChangeType());
//        hunkEntity.setOldPath(Objects.equals(diffHunk.getBaseHunk().getRelativeFilePath(), "") ? "/dev/null" : diffHunk.getBaseHunk().getRelativeFilePath());
//        hunkEntity.setNewPath(Objects.equals(diffHunk.getCurrentHunk().getRelativeFilePath(), "") ? "/dev/null" : diffHunk.getCurrentHunk().getRelativeFilePath());
//        hunkEntity.setBeginA(diffHunk.getBaseStartLine());
//        hunkEntity.setBeginB(diffHunk.getCurrentStartLine());
//        hunkEntity.setEndA(diffHunk.getBaseEndLine());
//        hunkEntity.setEndB(diffHunk.getCurrentEndLine());
//        hunks.add(hunkEntity);
//      }
//    }
//    return hunks;
//  }

  public List<HunkEntity> group2Hunks(Group group){
    List<HunkEntity> hunks = new ArrayList<>();
    for (String diffHunkID : group.getDiffHunkIDs()) {
      DiffHunk diffHunk = id2DiffHunkMap.get(diffHunkID.split(":")[1]);
      if (diffHunk != null) {
        HunkEntity hunkEntity = new HunkEntity();
        hunkEntity.setType(diffHunk.getChangeType());
        hunkEntity.setOldPath(Objects.equals(diffHunk.getBaseHunk().getRelativeFilePath(), "") ? "/dev/null" : diffHunk.getBaseHunk().getRelativeFilePath());
        hunkEntity.setNewPath(Objects.equals(diffHunk.getCurrentHunk().getRelativeFilePath(), "") ? "/dev/null" : diffHunk.getCurrentHunk().getRelativeFilePath());
        hunkEntity.setBeginA(diffHunk.getBaseStartLine());
        hunkEntity.setBeginB(diffHunk.getCurrentStartLine());
        hunkEntity.setEndA(diffHunk.getBaseEndLine());
        hunkEntity.setEndB(diffHunk.getCurrentEndLine());
        hunks.add(hunkEntity);
      }
    }
    return hunks;
  }
  /**
   * Analyze the changes collected
   *
   */
  public Map<String, Group> analyze(
          List<DiffFile> diffFiles, List<DiffHunk> allDiffHunks, Pair<String, String> srcDirs) throws Exception {
    try {
      buildRefGraphs(diffFiles, srcDirs);
    } catch (Exception e) {
      System.err.println("Exception during graph building:");
      e.printStackTrace();
    }

    // analyze the diff hunks
    generator =
        new GroupGenerator(
            repoID, repoName, srcDirs, diffFiles, allDiffHunks, baseGraph, currentGraph);
    generator.setMinSimilarity(minSimilarity);
    generator.setMaxDistance(maxDistance);
    generator.enableRefDetection(detectRefactorings);
    generator.enableNonJavaChanges(processNonJavaChanges);
    Utils.writeStringToFile(generator.buildDiffGraph(), tempDir + File.separator + "diffGraph.dot");
    return generator.generateGroups(weightThreshold);
  }

  /**
   * Build the Entity Reference Graphs for base and current versions
   *
   */
  private void buildRefGraphs(List<DiffFile> diffFiles, Pair<String, String> srcDirs)
      throws ExecutionException, InterruptedException, TimeoutException {
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    Future<Graph<Node, Edge>> baseBuilder =
        executorService.submit(new GraphBuilder(srcDirs.getLeft(), diffFiles));
    Future<Graph<Node, Edge>> currentBuilder =
        executorService.submit(new GraphBuilder(srcDirs.getRight(), diffFiles));
    baseGraph = baseBuilder.get(60 * 10, TimeUnit.SECONDS);
    currentGraph = currentBuilder.get(60 * 10, TimeUnit.SECONDS);
    //            String baseDot = GraphExporter.exportAsDotWithType(baseGraph);
    //            String currentDot = GraphExporter.exportAsDotWithType(currentGraph);
    executorService.shutdown();
  }

  /**
   * Solely for evaluation with ClusterChanges
   *
   */
  public Map<String, Group> analyzeWithCC(
      List<DiffFile> diffFiles, List<DiffHunk> allDiffHunks, Pair<String, String> srcDirs) {
    if (baseGraph == null && currentGraph == null) {
      try {
        buildRefGraphs(diffFiles, srcDirs);
      } catch (Exception e) {
        System.err.println("Exception during graph building:");
        e.printStackTrace();
      }
    }

    // analyze the diff hunks
    GroupGenerator generator =
        new GroupGenerator(
            repoID, repoName, srcDirs, diffFiles, allDiffHunks, baseGraph, currentGraph);
    generator.buildDiffGraph();
    return generator.clusterChanges();
  }

  /**
   * Analyze with one specific type of links for ablation
   *
   * @param filters: filter one or several types of links: 0 hard, 1 soft, 2 pattern, 3 logical
   */
  public Map<String, Group> analyzeWithAblation(
      List<DiffFile> diffFiles,
      List<DiffHunk> allDiffHunks,
      Pair<String, String> srcDirs,
      int... filters) {

    // only build ref graphs if they are null
    if (baseGraph == null && currentGraph == null) {
      try {
        buildRefGraphs(diffFiles, srcDirs);
      } catch (Exception e) {
        System.err.println("Exception during graph building:");
        e.printStackTrace();
      }
    }

    // analyze the diff hunks
    GroupGenerator generator =
        new GroupGenerator(
            repoID, repoName, srcDirs, diffFiles, allDiffHunks, baseGraph, currentGraph);
    generator.setMinSimilarity(minSimilarity);
    generator.setMaxDistance(maxDistance);
    generator.enableRefDetection(detectRefactorings);
    generator.enableNonJavaChanges(processNonJavaChanges);
    generator.buildDiffGraph();
    return generator.generateGroups(weightThreshold, filters);
  }

  /**
   * Save meta information of each group, including diff hunk ids, commit msgs, etc.
   *
   * @param generatedGroups generated groups <id:group>
   * @param outputDir output directory path
   */
  public void exportGroupResults(Map<String, Group> generatedGroups, String outputDir) {
    Utils.moveFile(tempDir, outputDir,  "diffGraph.dot");
//    Utils.moveFile(tempDir, outputDir,  "groupGraph.dot");
    com.github.smartcommit.util.Executor executor = new Executor();
    executor.setDirectory(new File(outputDir));
    executor.exec("dot -Tpng diffGraph.dot -o diffGraph.png");
    executor.exec("dot -Tpng groupGraph.dot -o groupGraph.png");
    Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    for (Map.Entry<String, Group> entry : generatedGroups.entrySet()) {
      Utils.writeStringToFile(
          gson.toJson(entry.getValue()),
          outputDir
              + File.separator
              + "generated_groups"
              + File.separator
              + entry.getKey()
              + ".json");
      // any manual adjustments will be made on this copy
      Utils.writeStringToFile(
          gson.toJson(entry.getValue()),
          outputDir + File.separator + "manual_groups" + File.separator + entry.getKey() + ".json");
    }
  }

  /**
   * Generate and save the detailed content of diff hunks for each group
   *
   * @param results generated groups <id:group>
   * @param outputDir output directory path
   */
  public void exportGroupDetails(Map<String, Group> results, String outputDir) {
    Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    List<String> groupedDiffHunks = new ArrayList<>();
    for (Map.Entry<String, Group> entry : results.entrySet()) {
      String path = outputDir + File.separator + entry.getKey() + ".json";
      StringBuilder builder = new StringBuilder();
      builder.append(entry.getValue().getIntentLabel()).append("\n");
      builder.append(entry.getValue().getCommitMsg()).append("\n");
      // check for duplication
      for (String id : entry.getValue().getDiffHunkIDs()) {
        if (groupedDiffHunks.contains(id)) {
          DiffHunk diffHunk = id2DiffHunkMap.get(id.split(":")[1]);
          logger.error("Duplicate DiffHunk: " + diffHunk.getUniqueIndex());
        }
        groupedDiffHunks.add(id);
        String[] pair = id.split(":");
        String diffHunkID;
        if (pair.length == 2) {
          diffHunkID = pair[1];
        } else if (pair.length == 1) {
          diffHunkID = pair[0];
        } else {
          logger.error("Invalid id: " + id);
          continue;
        }
        builder.append("------------").append("\n");
        DiffHunk diffHunk = id2DiffHunkMap.get(diffHunkID);
        builder.append(diffHunk.getUniqueIndex()).append("\n");
        builder.append(diffHunk.getDescription()).append("\n");
        builder.append(gson.toJson(diffHunk.getBaseHunk())).append("\n");
        builder.append(gson.toJson(diffHunk.getCurrentHunk())).append("\n");
      }
      Utils.writeStringToFile(builder.toString(), path);
    }

    if (groupedDiffHunks.size() != id2DiffHunkMap.keySet().size()) {
      logger.error(
          "Incorrect #diffhunks: Actual/Expected= "
              + groupedDiffHunks.size()
              + "/"
              + id2DiffHunkMap.keySet().size());
    }
  }

  /**
   * Read selected group json file, generate patches that can be applied incrementally for
   * inter-versions
   *
   */
  public void exportPatches(List<String> selectedGroupIDs) throws FileNotFoundException {
    String manualGroupsDir = tempDir + File.separator + "manual_groups";
    String fileDiffsDir = tempDir + File.separator + "diffs";
    String patchesDir = tempDir + File.separator + "patches";
    Utils.clearDir(patchesDir);

    List<String> groupFilePaths = Utils.listAllJsonFilePaths(manualGroupsDir);
    Gson gson = new Gson();
    for (String path : groupFilePaths) {
      StringBuilder builder = new StringBuilder();
      // read and parse group json file
      JsonReader reader = new JsonReader(new FileReader(path));
      Group group = gson.fromJson(reader, Group.class);
      // put diff hunks within the same file together
      Map<String, List<String>> fileID2hunkIDsMap = new HashMap<>();
      for (String id : group.getDiffHunkIDs()) {
        Pair<String, String> idPair = Utils.parseUUIDs(id);
        String fileID = idPair.getLeft();
        String diffHunkID = idPair.getRight();
        if (fileID2hunkIDsMap.containsKey(fileID)) {
          fileID2hunkIDsMap.get(fileID).add(diffHunkID);
        } else {
          List<String> temp = new ArrayList<>();
          temp.add(diffHunkID);
          fileID2hunkIDsMap.put(fileID, temp);
        }
      }

      // read and parse the diff json by file id
      for (Map.Entry<String, List<String>> entry : fileID2hunkIDsMap.entrySet()) {
        String fileDiffPath = fileDiffsDir + File.separator + entry.getKey() + ".json";
        reader = new JsonReader(new FileReader(fileDiffPath));
        DiffFile diffFile = gson.fromJson(reader, DiffFile.class);
        // get headers and raw diffs
        builder
            .append(String.join(System.lineSeparator(), diffFile.getRawHeaders()))
            .append(System.lineSeparator());
        for (String diffHunkID : entry.getValue()) {
          DiffHunk diffHunk = diffFile.getDiffHunksMap().getOrDefault(diffHunkID, null);
          if (diffHunk != null) {
            builder
                .append(String.join(System.lineSeparator(), diffHunk.getRawDiffs()))
                .append(System.lineSeparator());
          } else {
            logger.error("Null diffHunk with id: " + diffHunkID);
          }
        }
      }
      // save patches in temp dir
      String resultPath = patchesDir + File.separator + group.getGroupID() + ".patch";
      Utils.writeStringToFile(builder.toString(), resultPath);
    }
  }

  /**
   * Generate commit message for a given group
   *
   */
  public List<String> generateCommitMsg(Group group) {
    // get the ast actions and refactoring actions
    List<String> diffHunkIDs = group.getDiffHunkIDs();
    List<Action> astActions = new ArrayList<>();
    List<Action> refActions = new ArrayList<>();
    for (String id : diffHunkIDs) {
      DiffHunk diffHunk = id2DiffHunkMap.getOrDefault(id.split(":")[1], null);
      if (diffHunk != null) {
        astActions.addAll(diffHunk.getAstActions());
        refActions.addAll(diffHunk.getRefActions());
      }
    }

    //    CommitMsgGenerator generator = new CommitMsgGenerator(astActions, refActions);
    //    List<Integer> vectors = generator.generateGroupVector();
    //    MsgClass msgClass = generator.invokeAIModel(vectors);
    //    return generator.generateDetailedMsgs(msgClass, group.getIntentLabel());
    return new ArrayList<>();
  }

  /**
   * Commit all the selected groups with the given commit messages
   *
   * @param commitMsgs "group1":"Feature ...."
   */
  public boolean commit(List<String> selectedGroupIDs, Map<String, String> commitMsgs) {
    GitServiceCGit gitService = new GitServiceCGit();
    // clear the working dir firstly to prepare for applying patches
    if (gitService.clearWorkingTree(repoPath)) {
      for (String id : selectedGroupIDs) {
        String msg = commitMsgs.getOrDefault(id, "<Empty Commit Message>");
        // git apply patchX.patch
        // git add .
        // git commit -m "XXX"
      }

      // after all selected groups committed, stash the remaining changes
      // combine all uncommitted patches

      // apply the patches (TODO: base has changed)

      // stash the working tree

      return true;
    } else {
      logger.error("Failed to clear the working tree.");
      return false;
    }
  }

  public static void testGroups(Regression regression) throws Exception {
    String projectName = regression.getProjectFullName();
    Revision ric = regression.getRic();
    Revision work = regression.getWork();
    String regressionId = regression.getId();

    SmartCommit smartCommit = new SmartCommit(String.valueOf(projectName.hashCode()),
            projectName, Config.REPO_PATH + File.separator + projectName, Config.TEMP_DIR + projectName);
    Map<String, Group> groups = smartCommit.analyzeCommit(work.getCommitID(), ric.getCommitID());
    System.out.println("regression: " + regression.getId() + " group size: " + groups.size());
    Map<String, Integer> passGroups = new HashMap<>();
    Map<String, Integer> ceGroups = new HashMap<>();
    Set<HunkEntity> allHunks = new HashSet<>();
    String groupLabel = revertGroups(allHunks, groups,smartCommit,ric, passGroups, ceGroups);
    int hunkSum = allHunks.size();
    int minValue = 0;
    if(!passGroups.isEmpty()){
      minValue = Collections.min(passGroups.values());
    }
    System.out.println(regressionId +  ": GroupSize" + groups.size() + " HunkSum" + hunkSum + " PassGroupNum" + passGroups.size() + " MinHunkNum" + minValue);
    MysqlManager.insertGroupRevertResult("group_revert_result", regressionId, groups.size(), hunkSum, passGroups.size(), minValue, ceGroups.size(), groupLabel, getGroupLabelNum(groups));
  }

  private static String getGroupLabelNum(Map<String, Group>  groups){
    StringBuilder groupLabelNum = new StringBuilder();
    Map<GroupLabel, Integer> groupLabelMap = new HashMap<>();
    for(Map.Entry<String, Group> entry: groups.entrySet()){
      groupLabelMap.put(entry.getValue().getIntentLabel(), groupLabelMap.getOrDefault(entry.getValue().getIntentLabel(),0)+1);
    }
    for(Map.Entry<GroupLabel, Integer> entry: groupLabelMap.entrySet()){
      groupLabelNum.append(entry.getKey() + ":" + entry.getValue() + " ");
    }
    return groupLabelNum.toString();
  }

  public static String revertGroups(Set<HunkEntity> allHunks, Map<String, Group> groups, SmartCommit smartCommit, Revision ric,
                                  Map<String, Integer> passGroups, Map<String, Integer> ceGroups
                                  ) throws Exception {
    String minGroupLabel = "";

    for(Map.Entry<String, Group> entry: groups.entrySet()){
      List<HunkEntity> hunks = smartCommit.group2Hunks(entry.getValue());
      hunks.removeIf(hunkEntity -> hunkEntity.getNewPath().contains("test") || hunkEntity.getOldPath().contains("test"));
      if(hunks.size() == 0){
        continue;
      }
      String path = ric.getLocalCodeDir().toString().replace("_ric","_tmp");
      Utils.copyDirToTarget(ric.getLocalCodeDir().toString(),path);
      Revert.revert(path,hunks);
      Executor executor = new Executor();
      executor.setDirectory(new File(path));
      String execStatement = System.getProperty("user.home").contains("lsn") ?
              "chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh;" :
              "export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64; chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh; ";
      String result = executor.exec(execStatement).trim();
      System.out.println(entry.getKey() + ": Hunk size " + hunks.size() + "; Revert result " + result+ "; Group label " + entry.getValue().getIntentLabel());
      allHunks.addAll(hunks);
      if(result.contains("PASS")){
        passGroups.put(entry.getKey(),hunks.size());
        minGroupLabel = entry.getValue().getIntentLabel().name();
      }
      else if(result.contains("CE")){
        ceGroups.put(entry.getKey(),hunks.size());
      }
    }
    return minGroupLabel;
  }

  public static void revertGroups(Map<String, Group> groups, SmartCommit smartCommit, Revision ric,
                                  Map<String, Integer> passGroups, Map<String, Integer> ceGroups,
                                  Set<HunkEntity> allHunks) throws Exception {
    int a = 0;
    for(Map.Entry<String, Group> entry: groups.entrySet()){
      List<HunkEntity> hunks = smartCommit.group2Hunks(entry.getValue());
      hunks.removeIf(hunkEntity -> hunkEntity.getNewPath().contains("test") || hunkEntity.getOldPath().contains("test"));
      if(hunks.size() == 0){
        continue;
      }
      String path = ric.getLocalCodeDir().toString().replace("_ric","_tmp");
      Utils.copyDirToTarget(ric.getLocalCodeDir().toString(),path);
      Revert.revert(path,hunks);
      Executor executor = new Executor();
      executor.setDirectory(new File(path));
      String execStatement = System.getProperty("user.home").contains("lsn") ?
              "chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh;" :
              "export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64; chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh; ";
      String result = executor.exec(execStatement).trim();
      System.out.println(entry.getKey() + ": Hunk size " + hunks.size() + "; Revert result " + result+ "; Group label " + entry.getValue().getIntentLabel());
      allHunks.addAll(hunks);
      if(result.contains("PASS")){
        passGroups.put(entry.getKey(),hunks.size());
      }
      else if(result.contains("CE")){
        ceGroups.put(entry.getKey(),hunks.size());
      }
    }
  }

  public static void main(String [] args) throws Exception {
    Map<GroupLabel, Integer> groupLabelMap = new HashMap<>();
//    String sql = "select * from regressions_all where is_clean=1 and is_dirty=0 and id not in (select regression_id from group_revert_result);\n";
    String sql = "select * from regressions_all where is_clean=1 and is_dirty=0";
    List<Regression> regressionList = MysqlManager.selectCleanRegressions(sql);
    for (int i = 0; i < regressionList.size(); i++) {
      try{
        Regression regression = regressionList.get(i);
        String projectName = regression.getProjectFullName();
        if(!projectName.contains("verdict-project_verdict") && !projectName.contains("uklimaschewski_EvalEx")){
          continue;
        }
        Revision ric = regression.getRic();
        Revision work = regression.getWork();
        SmartCommit smartCommit = new SmartCommit(String.valueOf(projectName.hashCode()),
                projectName, Config.REPO_PATH + File.separator + projectName, Config.TEMP_DIR + projectName);
        Map<String, Group> groups = smartCommit.analyzeCommit(work.getCommitID(), ric.getCommitID());
        for(Map.Entry<String, Group> entry: groups.entrySet()){
          groupLabelMap.put(entry.getValue().getIntentLabel(), groupLabelMap.getOrDefault(entry.getValue().getIntentLabel(),0)+1);
          System.out.println(entry.getKey() + ": " + entry.getValue().getDiffHunkIDs().size() +  " " + entry.getValue().getIntentLabel());
        }
        System.out.println("regression: " + regression.getId() + " group size: " + groups.size());
      }
      catch (Exception e){
        e.printStackTrace();
      }
    }

    for(Map.Entry<GroupLabel, Integer> entry: groupLabelMap.entrySet()){
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
  }

  public Map<String, Double> getRank( Map<String, Group> groups, String testCase, String path){
    Map<String, Double> groupRank = new HashMap<>();
    String testCode = getTestCode(path, testCase);
    if(testCode == null || testCode.length() == 0){
      return groupRank;
    }
    try {
      StringBuilder args = new StringBuilder("testCode.txt");
      for (Map.Entry<String, Group> entry : groups.entrySet()) {
        String fileName = entry.getKey() + ".txt";  // 文件名
        // 创建文件对象
        File file = new File(fileName);
        FileWriter fileWriter = new FileWriter(file);
        BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
        for (String diffHunkID : entry.getValue().getDiffHunkIDs()) {
          DiffHunk diffHunk = id2DiffHunkMap.get(diffHunkID.split(":")[1]);
          List<String> delLen = diffHunk.getBaseHunk().getCodeSnippet();
          List<String> addLen = diffHunk.getCurrentHunk().getCodeSnippet();
          // 逐行写入内容
          for (String line : delLen) {
            bufferedWriter.write("- " + line);
            bufferedWriter.newLine(); // 写入换行符
          }
          for (String line : addLen) {
            bufferedWriter.write("+ " + line);
            bufferedWriter.newLine(); // 写入换行符
          }
          bufferedWriter.newLine(); // 写入换行符
        }
        bufferedWriter.close();
        args.append(" ").append(fileName);
      }
      String[] inputArray = new String(args).split("\\s+");

      String str = Utils.runPython("calScore.py", inputArray);
      Pattern pattern = Pattern.compile("(group\\d+)\\.txt\\s+Final score\\s*:\\s*(\\d+\\.\\d+)");
      Matcher matcher = pattern.matcher(str);

      while (matcher.find()) {
        String hunkCode = matcher.group(1);
        double finalScore = Double.parseDouble(matcher.group(2));
        groupRank.put(hunkCode, finalScore);
      }
    } catch (IOException e) {
      System.out.println("写入文件时出错：" + e.getMessage());
    }finally {

    }
    return groupRank;
  }


  private static double calScore(String args){
    try {
      String str = Utils.runPython("calScore.py", "testCode.txt", "hunkCode.txt");
      return Double.parseDouble(str);
    } catch (NumberFormatException e) {
      // 处理转换错误
      System.err.println("转换分数时出现数字格式错误：" + e.getMessage());
      return 0; // 或者返回一个默认值
    } catch (Exception e) {
      // 处理其他异常
      System.err.println("运行Python脚本时出现错误：" + e.getMessage());
      return -1; // 或者返回一个默认值
    }
  }

  //testPath： com.alibaba.json.bvt.parser.deser.list.ListFieldTest#test_for_list
  private String getTestCode(String basePath, String testPath) {
    final String[] testCode = {""};
    basePath += File.separator + "src" + File.separator + "test" + File.separator + "java" +  File.separator ;
    String[] parts = testPath.split("#");
    if (parts.length != 2) {
      System.out.println("Invalid test path format.");
      return null;
    }
    String className = parts[0];
    String methodName = parts[1];
    String filePath = basePath + className.replace(".", File.separator) + ".java";
    String source = "";
    try{
      source = new String(Files.readAllBytes(Paths.get(filePath)));
    } catch (IOException e) {
      System.out.println("Test file not found: " + filePath);
      return null;
    }
    ASTParser parser = ASTParser.newParser(AST.JLS8);
    parser.setSource(source.toCharArray());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);

    final CompilationUnit cu = (CompilationUnit) parser.createAST(null);

    cu.accept(new ASTVisitor() {
      public boolean visit(MethodDeclaration node) {
        if (node.getName().getFullyQualifiedName().equals(methodName)) {
          testCode[0] = node.toString();
        }
        return true;
      }
    });

    String fileName = "testCode.txt"; // 文件名
    File file = new File(fileName);

    try {
      // 创建文件写入器
      FileWriter fileWriter = new FileWriter(file);
      // 将字符串写入文件
      fileWriter.write(testCode[0]);
      // 关闭写入器
      fileWriter.close();
      System.out.println("test文件写入成功！");
    } catch (IOException e) {
      System.out.println("写入文件时出错：" + e.getMessage());
    }
    return testCode[0];
  }

}
