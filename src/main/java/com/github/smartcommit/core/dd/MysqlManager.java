package com.github.smartcommit.core.dd;

import com.github.smartcommit.model.Regression;
import com.github.smartcommit.model.Revision;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static com.github.smartcommit.client.Config.*;

/**
 * @author lsn
 * @date 2023/10/30 4:31 PM
 */
public class MysqlManager {

    public static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    private static Connection conn = null;
    private static Statement statement = null;


    private static void getConn() throws Exception {
        if (conn != null) {
            return;
        }
        Class.forName(DRIVER);
        conn = DriverManager.getConnection(URL, NAME, PWD);

    }

    private static void getConn(String database) throws Exception {
        if (conn != null) {
            return;
        }
        Class.forName(DRIVER);
        if(database.equals("95")){
            conn = DriverManager.getConnection(URL_95, NAME_95, PWD_95);
        }else if(database.equals("99")){
            conn = DriverManager.getConnection(URL_99, NAME_99, PWD_99);
        }

    }

    public static void getStatement() throws Exception {
        if (conn == null) {
            getConn();
        }
        if (statement != null) {
            return;
        }
        statement = conn.createStatement();
    }

    public static void closed() {
        try {
            if (statement != null) {
                statement.close();
            }
            if (conn != null) {
                conn.close();
            }
        } catch (Exception e) {

        } finally {
            try {
                if (statement != null) {
                    statement.close();
                    statement = null;
                }
                if (conn != null) {
                    conn.close();
                    conn = null;
                }
            } catch (Exception e) {
            }
        }
    }

    public static void executeUpdate(String sql) {
        try {
            getStatement();
            statement.executeUpdate(sql);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closed();
        }
    }

    public static List<Regression> selectCleanRegressions(String sql) {
        List<Regression> regressionList = new ArrayList<>();
        try {
            getStatement();
            ResultSet rs = statement.executeQuery(sql);
            while (rs.next()) {
                Regression regression = new Regression();
                regression.setProjectFullName(rs.getString("project_name"));
                regression.setId(String.valueOf(rs.getInt("id")));
                regression.setRfc(new Revision(rs.getString("bfc"), "rfc"));
                regression.setRic(new Revision(rs.getString("bic"), "ric"));
                regression.setWork(new Revision(rs.getString("wc"), "work"));
                regression.setTestCase(rs.getString("testcase1").split(";")[0]);
                regressionList.add(regression);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closed();
        }
        return regressionList;
    }

    public static void countCC(String revision_name, String regression_uuid, String tool) throws Exception{
        if (conn == null) {
            getConn();
        }
        PreparedStatement pstmt = null;
        try {
            getStatement();
            ResultSet rs = statement.executeQuery("select count(*) from critical_change_dd where revision_name='" + revision_name +
                    "'and regression_uuid='" + regression_uuid +"'" + "and tool='" + tool +"'");
            while (rs.next()) {
                System.out.println(rs.getInt(1));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            if (pstmt!=null){
                pstmt.close();
            }
        }
    }

    public static void insertGroupRevertResult(String tableName, String regressionId, int groupsNum,int hunkNum, int passNum, int resultHunkNum, int ceNum) throws Exception {
        if (conn == null) {
            getConn();
        }
        PreparedStatement pstmt = null;
        try {
            pstmt =conn.prepareStatement("insert into " + tableName+ " (regression_id,group_num,hunk_num,pass_num,result_hunk_num,ce_num) " +
                    "values(?,?,?,?,?,?)");
            pstmt.setInt(1, Integer.parseInt(regressionId));
            pstmt.setInt(2,groupsNum);
            pstmt.setInt(3,hunkNum);
            pstmt.setInt(4,passNum);
            pstmt.setInt(5,resultHunkNum);
            pstmt.setInt(6,ceNum);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }finally {
            if (pstmt!=null){
                pstmt.close();
            }
        }
    }
    public static void insertGroupRevertResult(String tableName, String regressionId, int groupsNum,int hunkNum, int passNum, int resultHunkNum, int ceNum, String groupLabel, String allGroupLabel) throws Exception {
        if (conn == null) {
            getConn("95");
        }
        PreparedStatement pstmt = null;
        try {
            pstmt =conn.prepareStatement("insert into " + tableName+ " (regression_id,group_num,hunk_num,pass_num,result_hunk_num,ce_num, pass_group_label, all_group_label) " +
                    "values(?,?,?,?,?,?,?,?)");
            pstmt.setInt(1, Integer.parseInt(regressionId));
            pstmt.setInt(2,groupsNum);
            pstmt.setInt(3,hunkNum);
            pstmt.setInt(4,passNum);
            pstmt.setInt(5,resultHunkNum);
            pstmt.setInt(6,ceNum);
            pstmt.setString(7,groupLabel);
            pstmt.setString(8,allGroupLabel);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }finally {
            if (pstmt!=null){
                pstmt.close();
            }
        }
    }


    public static void insertRankGroupRevertResult(String tableName, String regressionId, String groupId,int hunkNum, String result, String groupLabel, Double rank) throws Exception {
        if (conn == null) {
            getConn("95");
        }
        PreparedStatement pstmt = null;
        try {
            pstmt =conn.prepareStatement("insert into " + tableName + " (regression_id, group_id, hunk_num, result, group_label, rank) " +
                    "values(?,?,?,?,?,?)");
            pstmt.setInt(1, Integer.parseInt(regressionId));
            pstmt.setString(2,groupId);
            pstmt.setInt(3,hunkNum);
            pstmt.setString(4,result);
            pstmt.setString(5,groupLabel);
            pstmt.setString(6,rank.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }finally {
            if (pstmt!=null){
                pstmt.close();
            }
        }
    }


    public static void  insertDD(String uuid,String revision,String cc_ddmin,String cc_ddj) throws Exception {
        if (conn == null) {
            getConn();
        }
        PreparedStatement pstmt = null;
        try {
            pstmt =conn.prepareStatement("insert into dd(regressionId,revision," +
                    "cc_ddmin,cc_ddj) values(?," +
                    "?,?,?)");
            pstmt.setString(1,uuid);
            pstmt.setString(2,revision);
            pstmt.setString(3,cc_ddmin);
            pstmt.setString(4,cc_ddj);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }finally {
            if (pstmt!=null){
                pstmt.close();
            }
        }
    }

    public static List<String> selectProjects(String sql) {
        List<String> result = new ArrayList<>();
        try {
            getStatement();
            ResultSet rs = statement.executeQuery(sql);
            while (rs.next()) {
                result.add(rs.getString("project_full_name"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closed();
        }
        return result;
    }
}
