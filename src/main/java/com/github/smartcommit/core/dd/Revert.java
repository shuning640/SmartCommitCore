package com.github.smartcommit.core.dd;

import com.github.smartcommit.client.Config;
import com.github.smartcommit.client.SmartCommit;
import com.github.smartcommit.model.Group;
import com.github.smartcommit.model.HunkEntity;
import com.github.smartcommit.model.Regression;
import com.github.smartcommit.model.Revision;
import com.github.smartcommit.util.Executor;
import com.github.smartcommit.util.GitUtils;
import com.github.smartcommit.util.Utils;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author lsn
 * @date 2023/10/30 4:09 PM
 */
public class Revert {
    static Reducer reducer = new Reducer();
    static Migrator migrator = new Migrator();
    static SourceCodeManager sourceCodeManager = new SourceCodeManager();

    public static void main(String [] args) throws Exception {
        String sql = "select * from regressions_all where is_clean=1 and is_dirty=0 and id not in (select regression_id from group_revert_result);\n";
//        String sql = "select * from regressions_all where id = 102";
        List<Regression> regressionList = MysqlManager.selectCleanRegressions(sql);
        PrintStream o = new PrintStream(new File("log.txt"));
//        System.setOut(o);
        for (int i = 0; i < regressionList.size(); i++) {
            try{
                Regression regression = regressionList.get(i);
                String projectName = regression.getProjectFullName();
                if(projectName.contains("verdict-project_verdict")){
                    System.out.println("regression: " + regression.getId() + " is verdict project");
                    continue;
                }

                File projectDir = sourceCodeManager.getProjectDir(regression.getProjectFullName());
                String regressionId = regression.getId();

                Revision rfc = regression.getRfc();
                File rfcDir = sourceCodeManager.checkout(regressionId, rfc, projectDir, projectName);
                rfc.setLocalCodeDir(rfcDir);

                if(!GitUtils.areCommitsConsecutive(rfcDir, regression.getWork().getCommitID(), regression.getRic().getCommitID())){
                    System.out.println("regression: " + regression.getId() + " is not consecutive");
                    FileUtils.deleteDirectory(rfcDir);
                    continue;
                }

                Revision ric = regression.getRic();
                File ricDir = sourceCodeManager.checkout(regressionId, ric, projectDir, projectName);
                ric.setLocalCodeDir(ricDir);

                Revision work = regression.getWork();
                File workDir = sourceCodeManager.checkout(regressionId, work, projectDir, projectName);
                work.setLocalCodeDir(workDir);

                List<Revision> needToTestMigrateRevisionList = Arrays.asList(ric, work);
                migrateTestAndDependency(rfc, needToTestMigrateRevisionList, regression.getTestCase());

                sourceCodeManager.createShell(regression.getId(), projectName, ric, regression.getTestCase());
                sourceCodeManager.createShell(regression.getId(), projectName, work, regression.getTestCase());

                SmartCommit smartCommit = new SmartCommit(String.valueOf(projectName.hashCode()),
                        projectName, Config.REPO_PATH + File.separator + projectName, Config.TEMP_DIR + projectName);
                Map<String, Group> groups = smartCommit.analyzeCommit(ric.getCommitID());
                System.out.println("regression: " + regression.getId() + " group size: " + groups.size());
                List<Integer> hunkNums = new ArrayList<>();
                Map<String, Integer> passGroups = new HashMap<>();
                Map<String, Integer> ceGroups = new HashMap<>();
                int a = 0;
                for(Map.Entry<String, Group> entry: groups.entrySet()){
                    List<HunkEntity> hunks = smartCommit.group2Hunks(entry.getValue());
                    hunks.removeIf(hunkEntity -> hunkEntity.getNewPath().contains("test") || hunkEntity.getOldPath().contains("test"));
                    if(hunks.size() == 0){
                        continue;
                    }
                    String path = ric.getLocalCodeDir().toString().replace("_ric","_tmp");
                    Utils.copyDirToTarget(ric.getLocalCodeDir().toString(),path);
                    revert(path,hunks);
                    Executor executor = new Executor();
                    executor.setDirectory(new File(path));
                    String result = executor.exec("export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64; " +
                            "chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh; ").trim();
                    System.out.println(entry.getKey() + ": Hunk size " + hunks.size() + "; Revert result " + result);
                    hunkNums.add(hunks.size());
                    if(result.contains("PASS")){
                        passGroups.put(entry.getKey(),hunks.size());
                    }
                    else if(result.contains("CE")){
                        ceGroups.put(entry.getKey(),hunks.size());
                    }
                }
                int hunkSum = hunkNums.stream().mapToInt(Integer::intValue).sum();
                int minValue = 0;
                if(!passGroups.isEmpty()){
                    minValue = Collections.min(passGroups.values());
                }

                System.out.println(regressionId +  ": GroupSize" + groups.size() + " HunkSum" + hunkSum + " PassGroupNum" + passGroups.size() + " MinHunkNum" + minValue);
                MysqlManager.insertGroupRevertResult(regressionId, groups.size(), hunkSum, passGroups.size(), minValue, ceGroups.size());

                FileUtils.deleteDirectory(rfcDir);
                FileUtils.deleteDirectory(ricDir);
                FileUtils.deleteDirectory(workDir);
                FileUtils.deleteDirectory(new File(ric.getLocalCodeDir().toString().replace("_ric","_tmp")));
            }
            catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    public static void revert(String path, List<HunkEntity> hunkEntities){
        try{
            Map<String,List<HunkEntity>> stringListMap = hunkEntities.stream().collect(Collectors.groupingBy(HunkEntity::getNewPath));
            for (Map.Entry<String,List<HunkEntity>> entry: stringListMap.entrySet()){
                revertFile(path,entry.getValue());
            }
        }catch (Exception exception){
            exception.printStackTrace();
        }
    }

    /**
     * 处理一个文件的revert
     * @param tmpPath 临时项目的全路径，后缀是"_tmp"
     * @param hunkEntities 需要退回的hunk
     */
    public static List<String> revertFile(String tmpPath, List<HunkEntity> hunkEntities){
        String workPath = tmpPath.substring(0,tmpPath.lastIndexOf("_")) + "_work";
        HunkEntity tmpHunk = hunkEntities.get(0);
        if(!Objects.equals(tmpHunk.getNewPath(), tmpHunk.getOldPath())){
            String fileFullOldPath = workPath + File.separator + tmpHunk.getOldPath();
            String fileFullNewPath = tmpPath + File.separator + tmpHunk.getOldPath();
            Utils.copyFileToTarget(fileFullOldPath,fileFullNewPath);
        }
        List<String> line = new ArrayList<>();
        if(!Objects.equals(tmpHunk.getNewPath(), File.separator + "dev" + File.separator + "null")){
            line = Utils.readListFromFile(tmpPath + File.separator + tmpHunk.getNewPath());
        }
        hunkEntities.sort(new Comparator<HunkEntity>() {
            @Override
            public int compare(HunkEntity p1, HunkEntity p2) {
                return p2.getBeginA() - p1.getBeginA();
            }
        });

        for(HunkEntity hunkEntity: hunkEntities){
            HunkEntity.HunkType type = hunkEntity.getType();
            switch (type){
                case DELETE:
                    List<String> newLine = getLinesFromWorkVersion(workPath,hunkEntity);
                    line.addAll(hunkEntity.getBeginB()-1,newLine);
                    break;
                case INSERT:
                    line.subList(hunkEntity.getBeginB()-1, hunkEntity.getEndB()).clear();
                    break;
                case REPLACE:
                    line.subList(hunkEntity.getBeginB()-1, hunkEntity.getEndB()).clear();
                    List<String> replaceLine = getLinesFromWorkVersion(workPath,hunkEntity);
                    line.addAll(hunkEntity.getBeginB()-1,replaceLine);
                    break;
                case EMPTY:
                    break;
            }
        }

        //oldPath是一个空文件的情况
        if(!Objects.equals(tmpHunk.getOldPath(), "/dev/null")){
            Utils.writeListToFile(tmpPath + File.separator +tmpHunk.getOldPath(),line);
        }else{
            Utils.writeListToFile(tmpPath + File.separator +tmpHunk.getNewPath(),line);
        }
        return line;
    }

    public static List<String> getLinesFromWorkVersion(String workPath, HunkEntity hunk){
        List<String> result = new ArrayList<>();
        List<String> line = Utils.readListFromFile(workPath + File.separator + hunk.getOldPath());
        result = line.subList(hunk.getBeginA()-1, hunk.getEndA());
        return result;
    }

    static void migrateTestAndDependency(Revision rfc, List<Revision> needToTestMigrateRevisionList, String testCase) {
        migrator.equipRfcWithChangeInfo(rfc);
        reducer.reduceTestCases(rfc, testCase);
        needToTestMigrateRevisionList.forEach(revision -> {
            migrator.migrateTestFromTo_0(rfc, revision);
        });
    }
}
