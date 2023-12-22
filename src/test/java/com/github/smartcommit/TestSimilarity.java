package com.github.smartcommit;

import com.github.smartcommit.util.Utils;
import com.zhixiangli.code.similarity.CodeSimilarity;
import com.zhixiangli.code.similarity.strategy.CosineSimilarity;
import org.junit.jupiter.api.Test;

/**
 * @author lsn
 * @date 2023/12/11 6:01 PM
 */
public class TestSimilarity {
    @Test
    public void testCodeSimilarity() {
        String a = "public static void main(String[] a) { System.out.println(12);}";
        String b = "public static void main(String[] args) {System.out.println(2);}";
        // default algorithm is Longest Common Subsequence.
        CodeSimilarity codeSimilarity = new CodeSimilarity();
        System.out.println("codeSimilarity " + codeSimilarity.get(a, b));
        // change similarity algorithm to Cosine Distance.
        CodeSimilarity cosineSimilarity = new CodeSimilarity(new CosineSimilarity());
        System.out.println("cosineSimilarity " + cosineSimilarity.get(a, b));
        System.out.println("Utils.cosineStringSimilarity: " + Utils.cosineStringSimilarity(a, b));
    }
    @Test
    public void testEmpty() {
        String a = " ";
        String b = "";
        // default algorithm is Longest Common Subsequence.
        CodeSimilarity codeSimilarity = new CodeSimilarity();
        System.out.println("codeSimilarity " + codeSimilarity.get(a, b));
        // change similarity algorithm to Cosine Distance.
        CodeSimilarity cosineSimilarity = new CodeSimilarity(new CosineSimilarity());
        System.out.println("cosineSimilarity " + cosineSimilarity.get(a, b));
        System.out.println("Utils.cosineStringSimilarity: " + Utils.cosineStringSimilarity(a, b));
    }

    @Test
    public void testComments() {
        String a = "//        String sql = \"select * from re\";\n" +
                "        List<Regression> regressionList = MysqlManager.selectCleanRegressions(sql);";
        String b = "//        String sql = \"select * from regressions_all where id = 102\";\n" +
                "        List<Regression> regressionList = MysqlManager.selectCleanRegressions(sql);";
        // default algorithm is Longest Common Subsequence.
        CodeSimilarity codeSimilarity = new CodeSimilarity();
        System.out.println("codeSimilarity " + codeSimilarity.get(a, b));
        // change similarity algorithm to Cosine Distance.
        CodeSimilarity cosineSimilarity = new CodeSimilarity(new CosineSimilarity());
        System.out.println("cosineSimilarity " + cosineSimilarity.get(a, b));
        System.out.println("Utils.cosineStringSimilarity: " + Utils.cosineStringSimilarity(a, b));
    }


}
