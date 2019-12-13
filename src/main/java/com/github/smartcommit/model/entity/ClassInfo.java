package com.github.smartcommit.model.entity;

import java.util.ArrayList;
import java.util.List;

public class ClassInfo {

  public String name;
  public String belongTo;
  public String visibility = "package";
  public boolean isAbstract = false;
  public boolean isFinal = false;
  public String superClassType;
  public List<String> superInterfaceTypeList = new ArrayList<>();
  public String comment = "";
  public String content;

  public String uniqueName() {
    return belongTo + "." + name;
  }
}
