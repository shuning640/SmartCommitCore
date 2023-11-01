package com.github.smartcommit.model;

/**
 * @author lsn
 * @date 2023/10/30 5:02 PM
 */
public class TestFile extends ChangedFile {
    public Type type;
    private String qualityClassName;


    public TestFile(String newPath) {
        super(newPath);
    }

    public String getQualityClassName() {
        return qualityClassName;
    }

    public void setQualityClassName(String qualityClassName) {
        this.qualityClassName = qualityClassName;
    }


}
