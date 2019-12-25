package com.github.smartcommit.model;

import com.github.smartcommit.model.entity.*;

import java.util.HashMap;
import java.util.Map;

public class EntityPool {
  private String srcDir;
  public Map<String, ClassInfo> classInfoMap;
  public Map<String, InterfaceInfo> interfaceInfoMap;
  public Map<String, MethodInfo> methodInfoMap;
  public Map<String, FieldInfo> fieldInfoMap;
  public Map<String, HunkInfo> hunkInfoMap;

  public EntityPool(String srcDir) {
    this.srcDir = srcDir;
    classInfoMap = new HashMap<>();
    interfaceInfoMap = new HashMap<>();
    methodInfoMap = new HashMap<>();
    fieldInfoMap = new HashMap<>();
    hunkInfoMap = new HashMap<>();
  }
}
