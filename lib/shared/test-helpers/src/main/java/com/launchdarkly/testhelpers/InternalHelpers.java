package com.launchdarkly.testhelpers;

import java.util.concurrent.TimeUnit;

abstract class InternalHelpers {
  static TimeUnit timeUnit(TimeUnit unit) {
    return unit == null ? TimeUnit.MILLISECONDS : unit;
  }
  
  static String timeDesc(long value, TimeUnit unit) {
    String unitName = timeUnit(unit).name().toLowerCase();
    return String.format("%d %s", value, value == 1 ? unitName.substring(0, unitName.length() - 1) : unitName);
  }
}
