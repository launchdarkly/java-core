package com.launchdarkly.sdk.server;

public class PrerequisiteEvalRecord {
  public final DataModel.FeatureFlag flag;
  public final DataModel.FeatureFlag prereqOfFlag;
  public final EvalResult result;

  public PrerequisiteEvalRecord(DataModel.FeatureFlag flag, DataModel.FeatureFlag prereqOfFlag, EvalResult result) {
    this.flag = flag;
    this.prereqOfFlag = prereqOfFlag;
    this.result = result;
  }
}
