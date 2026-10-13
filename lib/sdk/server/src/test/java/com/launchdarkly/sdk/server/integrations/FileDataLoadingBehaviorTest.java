package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.integrations.FileDataSourceBase.DataBuilder;
import com.launchdarkly.sdk.server.integrations.FileDataSourceBase.DataLoader;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.subsystems.SerializationException;
import com.launchdarkly.testhelpers.TempDir;
import com.launchdarkly.testhelpers.TempFile;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.fail;

/**
 * Pins the error behavior of the file data source's loader. A document that parses but holds a flag
 * that the data model rejects is not a file data error: the deserialization exception escapes as it
 * always has. The exception description requires a cause. The override source has its own loader
 * with different rules.
 */
@SuppressWarnings("javadoc")
public class FileDataLoadingBehaviorTest {
  private static final String MODEL_REJECTED_DOCUMENT = "{\"flags\":{\"f1\":{\"key\":\"f1\",\"rules\":5}}}";

  @Test
  public void modelRejectedDocumentEscapesTheLoaderAsARuntimeException() throws Exception {
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        file.setContents(MODEL_REJECTED_DOCUMENT);
        DataLoader loader = new DataLoader(FileData.dataSource().filePaths(file.getPath()).sources);
        try {
          loader.load(new DataBuilder(FileData.DuplicateKeysHandling.FAIL));
          fail("expected exception");
        } catch (FileDataException e) {
          fail("the loader must not convert a model rejection into a file data error");
        } catch (RuntimeException e) {
          assertThat(e, instanceOf(SerializationException.class));
        }
      }
    }
  }

  @Test
  public void modelRejectedDocumentEscapesTheSynchronizerAsARuntimeException() throws Exception {
    try (TempDir dir = TempDir.create()) {
      try (TempFile file = dir.tempFile(".json")) {
        file.setContents(MODEL_REJECTED_DOCUMENT);
        try (Synchronizer synchronizer = FileData.synchronizer().filePaths(file.getPath())
            .build(TestDataSourceBuildInputs.create(LDLogger.none()))) {
          try {
            synchronizer.next();
            fail("expected exception");
          } catch (RuntimeException e) {
            assertThat(e, instanceOf(SerializationException.class));
          }
        }
      }
    }
  }

  @Test
  public void exceptionDescriptionRequiresACause() {
    FileDataException withCause = new FileDataException("message", new RuntimeException("boom"), null);
    assertThat(withCause.getDescription(), equalTo("message [java.lang.RuntimeException: boom]"));

    // A duplicate key failure carries no cause. The file data source builds its own description for
    // that case rather than calling this method.
    FileDataException withoutCause = new FileDataException("message", null, null);
    try {
      withoutCause.getDescription();
      fail("expected exception");
    } catch (NullPointerException e) {
      // current behavior
    }
  }
}
