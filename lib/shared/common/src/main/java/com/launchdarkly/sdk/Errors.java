package com.launchdarkly.sdk;

abstract class Errors {
  private Errors() {}
  
  static final String ATTR_EMPTY = "attribute reference cannot be empty";
  static final String ATTR_EXTRA_SLASH = "attribute reference contained a double slash or a trailing slash";
  static final String ATTR_INVALID_ESCAPE =
      "attribute reference contained an escape character (~) that was not followed by 0 or 1";

  static final String CONTEXT_FROM_NULL_USER = "tried to use a null LDUser reference";
  static final String CONTEXT_NO_KEY = "context key must not be null or empty";
  static final String CONTEXT_KIND_CANNOT_BE_EMPTY = "context kind must not be empty in JSON";
  static final String CONTEXT_KIND_CANNOT_BE_KIND = "\"kind\" is not a valid context kind";
  static final String CONTEXT_KIND_INVALID_CHARS = "context kind contains disallowed characters";
  static final String CONTEXT_KIND_MULTI_FOR_SINGLE = "context of kind \"multi\" must be created with NewMulti or NewMultiBuilder";
  static final String CONTEXT_KIND_MULTI_WITH_NO_KINDS = "multi-kind context must contain at least one kind";
  static final String CONTEXT_KIND_MULTI_DUPLICATES = "multi-kind context cannot have same kind more than once";
}
