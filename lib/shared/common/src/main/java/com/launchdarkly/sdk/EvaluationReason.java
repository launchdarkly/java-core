package com.launchdarkly.sdk;

import com.google.gson.annotations.JsonAdapter;
import com.launchdarkly.sdk.json.JsonSerializable;

import java.util.Objects;

/**
 * Describes the reason that a flag evaluation produced a particular value.
 * <p>
 * This is returned within {@link EvaluationDetail} by the SDK's "variation detail" methods such as
 * {@code boolVariationDetail}.
 * <p>
 * Note that while {@link EvaluationReason} has subclasses as an implementation detail, the subclasses
 * are not public and may be removed in the future. Always use methods of the base class such as
 * {@link #getKind()} or {@link #getRuleIndex()} to inspect the reason.
 * <p>
 * LaunchDarkly defines a standard JSON encoding for evaluation reasons, used in analytics events.
 * {@link EvaluationReason} can be converted to and from JSON in any of these ways:
 * <ol>
 * <li> With {@link com.launchdarkly.sdk.json.JsonSerialization}.
 * <li> With Gson, if and only if you configure your {@code Gson} instance with
 * {@link com.launchdarkly.sdk.json.LDGson}.
 * <li> With Jackson, if and only if you configure your {@code ObjectMapper} instance with
 * {@link com.launchdarkly.sdk.json.LDJackson}.
 * </ol>
 */
@JsonAdapter(EvaluationReasonTypeAdapter.class)
public final class EvaluationReason implements JsonSerializable {
  private static boolean IN_EXPERIMENT = true;
  private static boolean NOT_IN_EXPERIMENT = false;

  /**
   * Enumerated type defining the possible values of {@link EvaluationReason#getKind()}.
   */
  public static enum Kind {
    /**
     * Indicates that the flag was off and therefore returned its configured off value.
     */
    OFF,
    /**
     * Indicates that the flag was on but the user did not match any targets or rules. 
     */
    FALLTHROUGH,
    /**
     * Indicates that the context key was specifically targeted for this flag.
     */
    TARGET_MATCH,
    /**
     * Indicates that the context matched one of the flag's rules.
     */
    RULE_MATCH,
    /**
     * Indicates that the flag was considered off because it had at least one prerequisite flag
     * that either was off or did not return the desired variation.
     */
    PREREQUISITE_FAILED,
    /**
     * Indicates that the flag could not be evaluated, e.g. because it does not exist or due to an unexpected
     * error. In this case the result value will be the default value that the caller passed to the client.
     * Check the errorKind property for more details on the problem.
     */
    ERROR;
  }
  
  /**
   * Enumerated type defining the possible values of {@link #getErrorKind()}.
   */
  public static enum ErrorKind {
    /**
     * Indicates that the caller tried to evaluate a flag before the client had successfully initialized.
     */
    CLIENT_NOT_READY,
    /**
     * Indicates that the caller provided a flag key that did not match any known flag.
     */
    FLAG_NOT_FOUND,
    /**
     * Indicates that there was an internal inconsistency in the flag data, e.g. a rule specified a nonexistent
     * variation. An error message will always be logged in this case.
     */
    MALFORMED_FLAG,
    /**
     * Indicates that the caller passed {@code null} for the user parameter, or the user lacked a key.
     */
    USER_NOT_SPECIFIED,
    /**
     * Indicates that the result value was not of the requested type, e.g. you called {@code boolVariationDetail}
     * but the value was an integer.
     */
    WRONG_TYPE,
    /**
     * Indicates that an unexpected exception stopped flag evaluation. An error message will always be logged
     * in this case, and the exception should be available via {@link #getException()}.
     */
    EXCEPTION
  }

  /**
   * Enumerated type defining the possible values of {@link #getBigSegmentsStatus()}.
   */
  public static enum BigSegmentsStatus {
    /**
     * Indicates that the Big Segment query involved in the flag evaluation was successful, and that
     * the segment state is considered up to date.
     */
    HEALTHY,
    /**
     * Indicates that the Big Segment query involved in the flag evaluation was successful, but that
     * the segment state may not be up to date.
     */
    STALE,
    /**
     * Indicates that Big Segments could not be queried for the flag evaluation because the SDK
     * configuration did not include a Big Segment store.
     */
    NOT_CONFIGURED,
    /**
     * Indicates that the Big Segment query involved in the flag evaluation failed, for instance due
     * to a database error.
     */
    STORE_ERROR
  }
  
  // static instances to avoid repeatedly allocating reasons for the same parameters
  private static final EvaluationReason OFF_INSTANCE = new EvaluationReason(Kind.OFF);
  private static final EvaluationReason FALLTHROUGH_INSTANCE = new EvaluationReason(Kind.FALLTHROUGH);
  private static final EvaluationReason FALLTHROUGH_INSTANCE_IN_EXPERIMENT = new EvaluationReason(Kind.FALLTHROUGH, IN_EXPERIMENT);
  private static final EvaluationReason TARGET_MATCH_INSTANCE = new EvaluationReason(Kind.TARGET_MATCH);
  private static final EvaluationReason ERROR_CLIENT_NOT_READY = new EvaluationReason(ErrorKind.CLIENT_NOT_READY, null);
  private static final EvaluationReason ERROR_FLAG_NOT_FOUND = new EvaluationReason(ErrorKind.FLAG_NOT_FOUND, null);
  private static final EvaluationReason ERROR_MALFORMED_FLAG = new EvaluationReason(ErrorKind.MALFORMED_FLAG, null);
  private static final EvaluationReason ERROR_USER_NOT_SPECIFIED = new EvaluationReason(ErrorKind.USER_NOT_SPECIFIED, null);
  private static final EvaluationReason ERROR_WRONG_TYPE = new EvaluationReason(ErrorKind.WRONG_TYPE, null);
  private static final EvaluationReason ERROR_EXCEPTION = new EvaluationReason(ErrorKind.EXCEPTION, null);
  
  private final Kind kind;
  private final int ruleIndex;
  private final String ruleId;
  private final String prerequisiteKey;
  private final boolean inExperiment;
  private final ErrorKind errorKind;
  private final Exception exception;
  private final BigSegmentsStatus bigSegmentsStatus;
  
  private EvaluationReason(Kind kind, int ruleIndex, String ruleId, String prerequisiteKey, boolean inExperiment,
      ErrorKind errorKind, Exception exception, BigSegmentsStatus bigSegmentsStatus) {
    this.kind = kind;
    this.ruleIndex = ruleIndex;
    this.ruleId = ruleId;
    this.prerequisiteKey = prerequisiteKey;
    this.inExperiment = inExperiment;
    this.errorKind = errorKind;
    this.exception = exception;
    this.bigSegmentsStatus = bigSegmentsStatus;
  }
  
  private EvaluationReason(Kind kind) {
    this(kind, -1, null, null, NOT_IN_EXPERIMENT, null, null, null);
  }
  
  private EvaluationReason(Kind kind, boolean inExperiment) {
    this(kind, -1, null, null, inExperiment, null, null, null);
  }
  
  private EvaluationReason(ErrorKind errorKind, Exception exception) {
    this(Kind.ERROR, -1, null, null, NOT_IN_EXPERIMENT, errorKind, exception, null);
  }
  
  /**
   * Returns an enum indicating the general category of the reason.
   * 
   * @return a {@link Kind} value
   */
  public Kind getKind()
  {
    return kind;
  }

  /**
   * The index of the rule that was matched (0 for the first rule in the feature flag),
   * if the {@code kind} is {@link Kind#RULE_MATCH}. Otherwise this returns -1.
   * 
   * @return the rule index or -1
   */
  public int getRuleIndex() {
    return ruleIndex;
  }
  
  /**
   * The unique identifier of the rule that was matched, if the {@code kind} is
   * {@link Kind#RULE_MATCH}. Otherwise {@code null}.
   * <p>
   * Unlike the rule index, this identifier will not change if other rules are added or deleted.
   * 
   * @return the rule identifier or null
   */
  public String getRuleId() {
    return ruleId;
  }
  
  /**
   * The key of the prerequisite flag that did not return the desired variation, if the
   * {@code kind} is {@link Kind#PREREQUISITE_FAILED}. Otherwise {@code null}.
   * 
   * @return the prerequisite flag key or null 
   */
  public String getPrerequisiteKey() {
    return prerequisiteKey;
  }

  /**
   * Whether the evaluation was part of an experiment. Returns true if the evaluation 
   * resulted in an experiment rollout *and* served one of the variations in the 
   * experiment.  Otherwise it returns false.
   * 
   * @return whether the evaluation was part of an experiment
   */
  public boolean isInExperiment() {
    return inExperiment;
  }

  /**
   * An enumeration value indicating the general category of error, if the
   * {@code kind} is {@link Kind#PREREQUISITE_FAILED}. Otherwise {@code null}.
   * 
   * @return the error kind or null
   */
  public ErrorKind getErrorKind() {
    return errorKind;
  }

  /**
   * The exception that caused the error condition, if the {@code kind} is
   * {@link EvaluationReason.Kind#ERROR} and the {@code errorKind} is {@link ErrorKind#EXCEPTION}.
   * Otherwise {@code null}. 
   * <p>
   * Note that the exception will not be included in the JSON serialization of the reason when it
   * appears in analytics events; it is only provided informationally for use by application code.
   * 
   * @return the exception instance
   */
  public Exception getException() {
    return exception;
  }

  /**
   * Describes the validity of Big Segment information, if and only if the flag evaluation required
   * querying at least one Big Segment. Otherwise it returns {@code null}.
   * <p>
   * Big Segments are a specific type of user segments. For more information, read the
   * <a href="https://docs.launchdarkly.com/home/users/big-segments">LaunchDarkly documentation
   * </a>.
   *
   * @return the {@link BigSegmentsStatus} from the evaluation or {@code null}
   */
  public BigSegmentsStatus getBigSegmentsStatus() {
    return bigSegmentsStatus;
  }

  /**
   * Returns a copy of this {@link EvaluationReason} with a specific {@link BigSegmentsStatus}
   * value.
   *
   * @param bigSegmentsStatus the new property value
   * @return a new reason object
   */
  public EvaluationReason withBigSegmentsStatus(BigSegmentsStatus bigSegmentsStatus) {
    return new EvaluationReason(kind, ruleIndex, ruleId, prerequisiteKey, inExperiment, errorKind,
        exception, bigSegmentsStatus);
  }

  /**
   * Returns a simple string representation of the reason.
   * <p>
   * This is a convenience method for debugging and any other use cases where a human-readable string is
   * helpful. The exact format of the string is subject to change; if you need to make programmatic
   * decisions based on the reason properties, use other methods like {@link #getKind()}.
   */
  @Override
  public String toString() {
    switch (kind) {
    case RULE_MATCH:
      return kind + "(" + ruleIndex + (ruleId == null ? "" : ("," + ruleId)) + ")";
    case PREREQUISITE_FAILED:
      return kind + "(" + prerequisiteKey + ")";
    case ERROR:
      return kind + "(" + errorKind + (exception == null ? "" : ("," + exception)) + ")";
    default:
      return getKind().name();
    }
  }
  
  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (other instanceof EvaluationReason) {
      EvaluationReason o = (EvaluationReason)other;
      return kind == o.kind && 
        ruleIndex == o.ruleIndex && 
        Objects.equals(ruleId, o.ruleId) &&
        Objects.equals(prerequisiteKey, o.prerequisiteKey) && 
        inExperiment == o.inExperiment &&
        Objects.equals(errorKind, o.errorKind) &&
        Objects.equals(exception, o.exception) &&
        Objects.equals(bigSegmentsStatus, o.bigSegmentsStatus);
    }
    return false;
  }
  
  @Override
  public int hashCode() {
    return Objects.hash(kind, ruleIndex, ruleId, prerequisiteKey, inExperiment, errorKind,
        exception, bigSegmentsStatus);
  }
  
  /**
   * Returns an instance whose {@code kind} is {@link Kind#OFF}.
   * 
   * @return a reason object
   */
  public static EvaluationReason off() {
    return OFF_INSTANCE;
  }

  /**
   * Returns an instance whose {@code kind} is {@link Kind#FALLTHROUGH}.
   * 
   * @return a reason object
   */
  public static EvaluationReason fallthrough() {
    return FALLTHROUGH_INSTANCE;
  }
  
  /**
   * Returns an instance whose {@code kind} is {@link Kind#FALLTHROUGH} and 
   * where the inExperiment parameter represents whether the evaluation was
   * part of an experiment.
   * 
   * @param inExperiment whether the evaluation was part of an experiment
   * @return a reason object
   */
  public static EvaluationReason fallthrough(boolean inExperiment) {
    return inExperiment ? FALLTHROUGH_INSTANCE_IN_EXPERIMENT : FALLTHROUGH_INSTANCE;
  }
  
  /**
   * Returns an instance whose {@code kind} is {@link Kind#TARGET_MATCH}.
   * 
   * @return a reason object
   */
  public static EvaluationReason targetMatch() {
    return TARGET_MATCH_INSTANCE;
  }
  
  /**
   * Returns an instance whose {@code kind} is {@link Kind#RULE_MATCH}.
   * 
   * @param ruleIndex the rule index
   * @param ruleId the rule identifier
   * @return a reason object
   */
  public static EvaluationReason ruleMatch(int ruleIndex, String ruleId) {
    return ruleMatch(ruleIndex, ruleId, NOT_IN_EXPERIMENT);
  }
  
  /**
   * Returns an instance whose {@code kind} is {@link Kind#RULE_MATCH} and 
   * where the inExperiment parameter represents whether the evaluation was
   * part of an experiment.
   * 
   * @param ruleIndex the rule index
   * @param ruleId the rule identifier
   * @param inExperiment whether the evaluation was part of an experiment
   * @return a reason object
   */
  public static EvaluationReason ruleMatch(int ruleIndex, String ruleId, boolean inExperiment) {
    return new EvaluationReason(Kind.RULE_MATCH, ruleIndex, ruleId, null, inExperiment, null, null, null);
  }
  
  /**
   * Returns an instance whose {@code kind} is {@link Kind#PREREQUISITE_FAILED}.
   * 
   * @param prerequisiteKey the flag key of the prerequisite that failed 
   * @return a reason object
   */
  public static EvaluationReason prerequisiteFailed(String prerequisiteKey) {
    return new EvaluationReason(Kind.PREREQUISITE_FAILED, -1, null, prerequisiteKey, NOT_IN_EXPERIMENT, null, null, null);
  }
  
  /**
   * Returns an instance whose {@code kind} is {@link Kind#ERROR}.
   * 
   * @param errorKind describes the type of error
   * @return a reason object
   */
  public static EvaluationReason error(ErrorKind errorKind) {
    switch (errorKind) {
    case CLIENT_NOT_READY: return ERROR_CLIENT_NOT_READY;
    case EXCEPTION: return ERROR_EXCEPTION;
    case FLAG_NOT_FOUND: return ERROR_FLAG_NOT_FOUND;
    case MALFORMED_FLAG: return ERROR_MALFORMED_FLAG;
    case USER_NOT_SPECIFIED: return ERROR_USER_NOT_SPECIFIED;
    case WRONG_TYPE: return ERROR_WRONG_TYPE;
    default: return new EvaluationReason(errorKind, null); // COVERAGE: compiler requires default but there are no other ErrorKind values
    }
  }

  /**
   * Returns an instance whose {@code kind} is {@link Kind#ERROR}, with an exception instance.
   * <p>
   * Note that the exception will not be included in the JSON serialization of the reason when it
   * appears in analytics events; it is only provided informationally for use by application code.
   * 
   * @param exception the exception that caused the error
   * @return a reason object
   */
  public static EvaluationReason exception(Exception exception) {
    return new EvaluationReason(ErrorKind.EXCEPTION, exception);
  }
}
