package com.github.smartcommit.model.constant;

/**
 * @author lsn
 * @date 2024/4/1 4:34 PM
 */
public enum DiffHunkLabel {
    NONJAVA("Modify non-java file"),
    TEST("Modify test cases"),
    FEATURE("Add feature"), // new feature
    REFACTOR("Refactor code structure"), // refactoring
    REFORMAT("Reformat code"), // blank/special character changes
    OTHER("Other changes"), // trivial changes
    DOCS("Modifydocs file");

    public String label;

    DiffHunkLabel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
