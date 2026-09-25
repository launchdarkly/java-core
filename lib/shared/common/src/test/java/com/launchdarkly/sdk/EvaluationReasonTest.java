package com.launchdarkly.sdk;

import static com.launchdarkly.sdk.EvaluationReason.BigSegmentsStatus.HEALTHY;
import static com.launchdarkly.sdk.EvaluationReason.BigSegmentsStatus.STALE;
import static com.launchdarkly.sdk.EvaluationReason.ErrorKind.CLIENT_NOT_READY;
import static com.launchdarkly.sdk.EvaluationReason.ErrorKind.FLAG_NOT_FOUND;
import static com.launchdarkly.sdk.EvaluationReason.ErrorKind.WRONG_TYPE;
import static com.launchdarkly.sdk.EvaluationReason.Kind.ERROR;
import static com.launchdarkly.sdk.EvaluationReason.Kind.FALLTHROUGH;
import static com.launchdarkly.sdk.EvaluationReason.Kind.OFF;
import static com.launchdarkly.sdk.EvaluationReason.Kind.PREREQUISITE_FAILED;
import static com.launchdarkly.sdk.EvaluationReason.Kind.RULE_MATCH;
import static com.launchdarkly.sdk.EvaluationReason.Kind.TARGET_MATCH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static java.util.Arrays.asList;

import org.junit.Test;

import java.util.List;

@SuppressWarnings("javadoc")
public class EvaluationReasonTest extends BaseTest {
  @Test
  public void basicProperties() {
    assertEquals(OFF, EvaluationReason.off().getKind());
    assertEquals(FALLTHROUGH, EvaluationReason.fallthrough().getKind());
    assertEquals(TARGET_MATCH, EvaluationReason.targetMatch().getKind());
    assertEquals(RULE_MATCH, EvaluationReason.ruleMatch(1, "id").getKind());
    assertEquals(PREREQUISITE_FAILED, EvaluationReason.prerequisiteFailed("key").getKind());
    assertEquals(ERROR, EvaluationReason.error(FLAG_NOT_FOUND).getKind());
    
    assertEquals(1, EvaluationReason.ruleMatch(1, "id").getRuleIndex());
    assertEquals(-1, EvaluationReason.off().getRuleIndex());
    assertEquals(-1, EvaluationReason.fallthrough().getRuleIndex());
    assertEquals(-1, EvaluationReason.targetMatch().getRuleIndex());
    assertEquals(-1, EvaluationReason.prerequisiteFailed("key").getRuleIndex());
    assertEquals(-1, EvaluationReason.error(FLAG_NOT_FOUND).getRuleIndex());

    assertEquals("id", EvaluationReason.ruleMatch(1, "id").getRuleId());
    assertNull(EvaluationReason.off().getRuleId());
    assertNull(EvaluationReason.fallthrough().getRuleId());
    assertNull(EvaluationReason.targetMatch().getRuleId());
    assertNull(EvaluationReason.prerequisiteFailed("key").getRuleId());
    assertNull(EvaluationReason.error(FLAG_NOT_FOUND).getRuleId());
    
    assertEquals("key", EvaluationReason.prerequisiteFailed("key").getPrerequisiteKey());
    assertNull(EvaluationReason.off().getPrerequisiteKey());
    assertNull(EvaluationReason.fallthrough().getPrerequisiteKey());
    assertNull(EvaluationReason.targetMatch().getPrerequisiteKey());
    assertNull(EvaluationReason.ruleMatch(1, "id").getPrerequisiteKey());
    assertNull(EvaluationReason.error(FLAG_NOT_FOUND).getPrerequisiteKey());
    
    assertEquals(FLAG_NOT_FOUND, EvaluationReason.error(FLAG_NOT_FOUND).getErrorKind());
    assertNull(EvaluationReason.off().getErrorKind());
    assertNull(EvaluationReason.fallthrough().getErrorKind());
    assertNull(EvaluationReason.targetMatch().getErrorKind());
    assertNull(EvaluationReason.ruleMatch(1, "id").getErrorKind());
    assertNull(EvaluationReason.prerequisiteFailed("key").getErrorKind());
    
    Exception e = new Exception("sorry");
    assertEquals(e, EvaluationReason.exception(e).getException());
    assertNull(EvaluationReason.off().getException());
    assertNull(EvaluationReason.fallthrough().getException());
    assertNull(EvaluationReason.targetMatch().getException());
    assertNull(EvaluationReason.ruleMatch(1, "id").getException());
    assertNull(EvaluationReason.prerequisiteFailed("key").getException());
    assertNull(EvaluationReason.error(FLAG_NOT_FOUND).getException());
  }

  @Test
  public void bigSegmentsStatus() {
    assertNull(EvaluationReason.off().getBigSegmentsStatus());
    assertNull(EvaluationReason.fallthrough().getBigSegmentsStatus());
    assertNull(EvaluationReason.targetMatch().getBigSegmentsStatus());
    assertNull(EvaluationReason.ruleMatch(1, "id").getBigSegmentsStatus());
    assertNull(EvaluationReason.prerequisiteFailed("key").getBigSegmentsStatus());
    assertNull(EvaluationReason.error(FLAG_NOT_FOUND).getBigSegmentsStatus());

    EvaluationReason reason = EvaluationReason.fallthrough();
    EvaluationReason withStatus = reason.withBigSegmentsStatus(STALE);
    assertEquals(STALE, withStatus.getBigSegmentsStatus());
    assertNull(reason.getBigSegmentsStatus());
  }
  
  @Test
  public void overrideAffectedIsFalseByDefault() {
    assertFalse(EvaluationReason.off().isOverrideAffected());
    assertFalse(EvaluationReason.fallthrough().isOverrideAffected());
    assertFalse(EvaluationReason.fallthrough(true).isOverrideAffected());
    assertFalse(EvaluationReason.targetMatch().isOverrideAffected());
    assertFalse(EvaluationReason.ruleMatch(1, "id").isOverrideAffected());
    assertFalse(EvaluationReason.prerequisiteFailed("key").isOverrideAffected());
    assertFalse(EvaluationReason.error(FLAG_NOT_FOUND).isOverrideAffected());
    assertFalse(EvaluationReason.exception(new Exception("sorry")).isOverrideAffected());
    assertFalse(EvaluationReason.fallthrough().withBigSegmentsStatus(HEALTHY).isOverrideAffected());
  }

  @Test
  public void withOverrideAffectedKeepsOtherProperties() {
    EvaluationReason reason = EvaluationReason.ruleMatch(2, "id", true).withBigSegmentsStatus(STALE);
    EvaluationReason marked = reason.withOverrideAffected(true);

    assertTrue(marked.isOverrideAffected());
    assertFalse(reason.isOverrideAffected());
    assertEquals(RULE_MATCH, marked.getKind());
    assertEquals(2, marked.getRuleIndex());
    assertEquals("id", marked.getRuleId());
    assertTrue(marked.isInExperiment());
    assertEquals(STALE, marked.getBigSegmentsStatus());

    Exception e = new Exception("sorry");
    EvaluationReason markedError = EvaluationReason.exception(e).withOverrideAffected(true);
    assertEquals(ERROR, markedError.getKind());
    assertEquals(EvaluationReason.ErrorKind.EXCEPTION, markedError.getErrorKind());
    assertEquals(e, markedError.getException());
    assertTrue(markedError.isOverrideAffected());

    // The marking survives a later change of another property.
    assertTrue(marked.withBigSegmentsStatus(HEALTHY).isOverrideAffected());
  }

  @Test
  public void withOverrideAffectedReturnsSameInstanceWhenUnchanged() {
    EvaluationReason reason = EvaluationReason.off();
    assertSame(reason, reason.withOverrideAffected(false));
    EvaluationReason marked = reason.withOverrideAffected(true);
    assertSame(marked, marked.withOverrideAffected(true));
    assertFalse(marked.withOverrideAffected(false).isOverrideAffected());
  }

  @Test
  public void overrideAffectedParticipatesInEquality() {
    EvaluationReason plain = EvaluationReason.off();
    EvaluationReason marked = plain.withOverrideAffected(true);
    assertNotEquals(plain, marked);
    assertEquals(marked, plain.withOverrideAffected(true));
    assertEquals(marked.hashCode(), plain.withOverrideAffected(true).hashCode());
  }

  @Test
  public void overrideAffectedDoesNotChangeStringRepresentation() {
    assertEquals("OFF", EvaluationReason.off().withOverrideAffected(true).toString());
    assertEquals("RULE_MATCH(1,id)", EvaluationReason.ruleMatch(1, "id").withOverrideAffected(true).toString());
  }

  @Test
  public void simpleStringRepresentations() {
    assertEquals("OFF", EvaluationReason.off().toString());
    assertEquals("FALLTHROUGH", EvaluationReason.fallthrough().toString());
    assertEquals("FALLTHROUGH", EvaluationReason.fallthrough().withBigSegmentsStatus(HEALTHY).toString());
    assertEquals("TARGET_MATCH", EvaluationReason.targetMatch().toString());
    assertEquals("RULE_MATCH(1)", EvaluationReason.ruleMatch(1, null).toString());
    assertEquals("RULE_MATCH(1,id)", EvaluationReason.ruleMatch(1, "id").toString());
    assertEquals("RULE_MATCH(1,id)", EvaluationReason.ruleMatch(1, "id", false).toString());
    assertEquals("RULE_MATCH(1,id)", EvaluationReason.ruleMatch(1, "id", true).toString());
    assertEquals("PREREQUISITE_FAILED(key)", EvaluationReason.prerequisiteFailed("key").toString());
    assertEquals("ERROR(FLAG_NOT_FOUND)", EvaluationReason.error(FLAG_NOT_FOUND).toString());
    assertEquals("ERROR(EXCEPTION)", EvaluationReason.exception(null).toString());
    assertEquals("ERROR(EXCEPTION,java.lang.Exception: something happened)",
        EvaluationReason.exception(new Exception("something happened")).toString());
  }
  
  @Test
  public void instancesAreReused() {
    assertSame(EvaluationReason.off(), EvaluationReason.off());
    assertSame(EvaluationReason.fallthrough(), EvaluationReason.fallthrough());
    assertSame(EvaluationReason.targetMatch(), EvaluationReason.targetMatch());
    
    for (EvaluationReason.ErrorKind errorKind: EvaluationReason.ErrorKind.values()) {
      EvaluationReason r0 = EvaluationReason.error(errorKind);
      assertEquals(errorKind, r0.getErrorKind());
      EvaluationReason r1 = EvaluationReason.error(errorKind);
      assertSame(r0, r1);
    }
  }
  
  @Test
  public void equalInstancesAreEqual() {
    List<List<EvaluationReason>> testValues = asList(
        asList(EvaluationReason.off(), EvaluationReason.off()),
        asList(EvaluationReason.fallthrough(), EvaluationReason.fallthrough()),
        asList(EvaluationReason.fallthrough().withBigSegmentsStatus(HEALTHY),
               EvaluationReason.fallthrough().withBigSegmentsStatus(HEALTHY)),
        asList(EvaluationReason.targetMatch(), EvaluationReason.targetMatch()),
        asList(EvaluationReason.ruleMatch(1, "id1"), EvaluationReason.ruleMatch(1, "id1")),
        asList(EvaluationReason.ruleMatch(1, "id1", true), EvaluationReason.ruleMatch(1, "id1", true)),
        asList(EvaluationReason.ruleMatch(1, "id2"), EvaluationReason.ruleMatch(1, "id2")),
        asList(EvaluationReason.ruleMatch(2, "id1"), EvaluationReason.ruleMatch(2, "id1")),
        asList(EvaluationReason.prerequisiteFailed("a"), EvaluationReason.prerequisiteFailed("a")),
        asList(EvaluationReason.error(CLIENT_NOT_READY), EvaluationReason.error(CLIENT_NOT_READY)),
        asList(EvaluationReason.error(WRONG_TYPE), EvaluationReason.error(WRONG_TYPE)),
        asList(EvaluationReason.off().withOverrideAffected(true), EvaluationReason.off().withOverrideAffected(true)),
        asList(EvaluationReason.error(WRONG_TYPE).withOverrideAffected(true),
               EvaluationReason.error(WRONG_TYPE).withOverrideAffected(true))
    );
    TestHelpers.doEqualityTests(testValues);
  }
}
