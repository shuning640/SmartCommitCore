package com.github.smartcommit;

import com.github.smartcommit.core.GroupGenerator;
import com.github.smartcommit.util.Utils;
import com.zhixiangli.code.similarity.CodeSimilarity;
import com.zhixiangli.code.similarity.strategy.CosineSimilarity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestReformatting {
  @Test
  public void testWhitespace() {
    List<String> s1 = new ArrayList<>();
    s1.add("public static String formatPath(String path) {");
    List<String> s2 = new ArrayList<>();
    s2.add("public     static String formatPath(String path) {");
    s2.add("\t\n");
    String baseString = Utils.convertListLinesToString(s1);
    String currentString = Utils.convertListLinesToString(s2);
    assertTrue(GroupGenerator.detectReformatting(baseString, currentString)) ;
//    assertThat(Utils.convertListToStringNoFormat(s1))
//        .isEqualTo(Utils.convertListToStringNoFormat(s2));
  }

  @Test
  public void testIndentation() {

    List<String> s1 = new ArrayList<>();
    s1.add("public static String formatPath(String path) {");
    s1.add("}");
    List<String> s2 = new ArrayList<>();
    s2.add("      public static String formatPath(String path) {\n\");");
    s2.add("\t}");
    assertThat(Utils.convertListToStringNoFormat(s1))
        .isEqualTo(Utils.convertListToStringNoFormat(s2));
  }

  @Test
  public void testComment() {
    List<String> s1 = new ArrayList<>();
    s1.add("  /** Convert system-dependent path to the unified unix style */\n");
    s1.add("  public static String formatPath(String path) {\n");

    List<String> s2 = new ArrayList<>();
    s2.add("  /** ");
    s2.add("       * Convert system-dependent path to the unified unix style ");
    s2.add("*/\n");
    s2.add("  public static String formatPath(String path) {\n");

    List<String> s3 = new ArrayList<>();
    s3.add("  // Convert system-dependent path to the unified unix style\n");
    s3.add("  public static String formatPath(String path) {\n");

    String baseString = Utils.convertListLinesToString(s1);
    String currentString = Utils.convertListLinesToString(s2);
    assertTrue(GroupGenerator.detectReformatting(baseString, currentString)) ;

    currentString = Utils.convertListLinesToString(s3);
    assertTrue(GroupGenerator.detectReformatting(baseString, currentString)) ;
//    assertThat(Utils.convertListToStringNoFormat(s1))
//        .isEqualTo(Utils.convertListToStringNoFormat(s2));
  }

  @Test
  public void testPunctuation() {
    List<String> s1 = new ArrayList<>();

    s1.add("JAR(\".jar\"; \"Jar\"),");
    s1.add("XML(\".xml\"; \"XML\"),");
    s1.add("OTHER(\".*\"; \"Other\");");

    List<String> s2 = new ArrayList<>();
    s2.add("JAR(\".jar\", \"Jar\");");
    s2.add("XML(\".xml\", \"XML\");");
    s2.add("OTHER(\".*\", \"Other\");");

    assertThat(Utils.convertListToStringNoFormat(s1))
        .isEqualTo(Utils.convertListToStringNoFormat(s2));
  }
}
