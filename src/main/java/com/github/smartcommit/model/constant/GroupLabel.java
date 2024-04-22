package com.github.smartcommit.model.constant;

public enum GroupLabel {
  NONJAVA("Modify non-java file"),
  REFORMAT("Reformat code"), // blank/special character changes
  TEST("Modify test cases"),
  REFACTOR("Refactor code structure"), // refactoring
  FEATURE("Add feature"), // new feature
  FEATUREENHANCE("Feature Enhancement"), // feature enhancement
  OTHER("Other changes"), // trivial changes

  FIX("Fix bug"), // fix bugs
  OPT("Optimize code"), // optimization for existing functions
  DOC("Update document"),
  CONFIG("Change config file"),
  RESOURCE("Change resource file"),
  SIMILAR("Apply some similar changes"), // systematic changes
  CLEAR("Clear unused code"); // clear dead code or comment

  public String label;

  GroupLabel(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }
}
