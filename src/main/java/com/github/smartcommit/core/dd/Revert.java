package com.github.smartcommit.core.dd;

import com.github.smartcommit.client.Config;
import com.github.smartcommit.client.SmartCommit;
import com.github.smartcommit.model.Group;
import com.github.smartcommit.model.HunkEntity;
import com.github.smartcommit.model.Regression;
import com.github.smartcommit.model.Revision;
import com.github.smartcommit.util.Executor;
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
        String tableName = "group_revert_result";
//        String sql = "select * from regressions_all where is_clean=1 and is_dirty=0 and id not in (select regression_id from " + tableName + ");\n";
        String sql = "select * from regressions_all where is_clean=1 and is_dirty=0 and id > 199;\n";
        List<Regression> regressionList = MysqlManager.selectCleanRegressions(sql);
        PrintStream o = new PrintStream(new File("log_" + tableName +".txt"));
        System.setOut(o);
        batchRegressionRevert(regressionList);
    }


    public static void batchRegressionRevert(List<Regression> regressionList) {
        for (int i = 0; i < regressionList.size(); i++) {
            try{
                System.out.println("revert regression: " + regressionList.get(i).getId());
                Regression regression = regressionList.get(i);
                regressionRevert(regression);
            }
            catch (Exception e){
                System.out.println(e);
                e.printStackTrace();
            }
        }
    }

    public static void regressionRevert(Regression regression) throws Exception {
        String projectName = regression.getProjectFullName();
        if(projectName.contains("verdict-project_verdict") || projectName.contains("uklimaschewski_EvalEx")){
            System.out.println("regression: " + regression.getId() + " is " + projectName);
            return;
        }

        File projectDir = sourceCodeManager.getProjectDir(regression.getProjectFullName());
        String regressionId = regression.getId();

        Revision rfc = regression.getRfc();
        File rfcDir = sourceCodeManager.checkout(regressionId, rfc, projectDir, projectName);
        rfc.setLocalCodeDir(rfcDir);

//        if(!GitUtils.areCommitsConsecutive(rfcDir, regression.getWork().getCommitID(), regression.getRic().getCommitID())){
//            System.out.println("regression: " + regression.getId() + " is not consecutive");
//            FileUtils.deleteDirectory(rfcDir);
//            return;
//        }

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

        String path = work.getLocalCodeDir().toString().replace("_work","_tmp");
        Utils.copyDirToTarget(work.getLocalCodeDir().toString(),path);
        Executor executor = new Executor();
        executor.setDirectory(new File(path));
        String execStatement = System.getProperty("user.home").contains("lsn") ?
                "chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh;" :
                "export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64; chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh; ";
        String result = executor.exec(execStatement).trim();
        if(!result.contains("PASS")){
            System.out.println("regression: " + regression.getId() + " work version test failed");
            return;
        }
//        SmartCommit.testGroups(regression);

        SmartCommit smartCommit = new SmartCommit(String.valueOf(projectName.hashCode()),
                projectName, Config.REPO_PATH + File.separator + projectName, Config.TEMP_DIR + projectName);
        Map<String, Group> groups = smartCommit.analyzeCommit(work.getCommitID(), ric.getCommitID());
        Map<String, Double> groupRank = smartCommit.getRank(groups, regression.getTestCase(), path);
        for(Map.Entry<String, Group> entry: groups.entrySet()){
            List<HunkEntity> hunks = smartCommit.group2Hunks(entry.getValue());
            int hunksSize = hunks.size();
            hunks.removeIf(hunkEntity -> hunkEntity.getNewPath().contains("test") || hunkEntity.getOldPath().contains("test"));
            if(hunks.size() == 0){
                continue;
            }
            Utils.copyDirToTarget(ric.getLocalCodeDir().toString(),path);
            Revert.revert(path,hunks);
            execStatement = System.getProperty("user.home").contains("lsn") ?
                    "chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh;" :
                    "export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64; chmod u+x build.sh; chmod u+x test.sh; ./build.sh; ./test.sh; ";
            result = executor.exec(execStatement).trim();
            System.out.println("regression" + regressionId +" " + entry.getKey() + " " + entry.getValue().getIntentLabel() +
                    " " +  hunksSize + " " + result + " " + groupRank.get(entry.getKey()));
            MysqlManager.insertRankGroupRevertResult("rank_group_revert_result", regressionId, entry.getKey(), hunksSize,result,
                    entry.getValue().getIntentLabel().getLabel(), groupRank.get(entry.getKey()));
        }

        FileUtils.deleteDirectory(rfcDir);
        FileUtils.deleteDirectory(ricDir);
        FileUtils.deleteDirectory(workDir);
        FileUtils.deleteDirectory(new File(ric.getLocalCodeDir().toString().replace("_ric","_tmp")));
    }

    public static void revert(String path, List<HunkEntity> hunkEntities){
        try{
            Map<String,List<HunkEntity>> stringListMap = hunkEntities.stream().collect(Collectors.groupingBy(entity -> entity.getNewPath() + "-" + entity.getOldPath()));
            for (Map.Entry<String,List<HunkEntity>> entry: stringListMap.entrySet()){
                revertFile(path,entry.getValue());
            }
        }catch (Exception exception){
            System.out.println(path + "revert error");
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
                    line.addAll(Math.max(hunkEntity.getBeginB(), 0),newLine);
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
