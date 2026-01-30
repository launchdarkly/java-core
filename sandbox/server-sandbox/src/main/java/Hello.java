import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.sdk.*;
import com.launchdarkly.sdk.server.*;
import com.launchdarkly.sdk.server.integrations.FileData;


public class Hello {

  static String SDK_KEY = "";
  static String FEATURE_FLAG_KEY = "my-boolean-flag";

  private static void showMessage(String s) {
    System.out.println("*** " + s);
    System.out.println();
  }

  private static void showBanner() {
    showMessage("\n        ██       \n" +
                "          ██     \n" +
                "      ████████   \n" +
                "         ███████ \n" +
                "██ LAUNCHDARKLY █\n" +
                "         ███████ \n" +
                "      ████████   \n" +
                "          ██     \n" +
                "        ██       \n");
  }

  public static void main(String... args) throws Exception {
    boolean CIMode = System.getenv("CI") != null;

    String envSDKKey = System.getenv("LAUNCHDARKLY_SDK_KEY");
    if(envSDKKey != null) {
      SDK_KEY = envSDKKey;
    }

    String envFlagKey = System.getenv("LAUNCHDARKLY_FLAG_KEY");
    if(envFlagKey != null) {
      FEATURE_FLAG_KEY = envFlagKey;
    }

    LDConfig config = new LDConfig.Builder()
//      .serviceEndpoints(Components.serviceEndpoints()
//        .polling("http://localhost:3002/proxy-poll")
//        .streaming("http://localhost:3001/proxy"))
        .dataSource(FileData.dataSource().filePaths("stuff/flagdata.json").autoUpdate(true))
      .logging(Components.logging().level(LDLogLevel.DEBUG))
//            .dataSystem(
//                Components.dataSystem().custom().synchronizers(
//                    FileData.synchronizer().filePaths("flagdata.json").autoUpdate(true)))
            .build();

    if (SDK_KEY == null || SDK_KEY.equals("")) {
      showMessage("Please set the LAUNCHDARKLY_SDK_KEY environment variable or edit Hello.java to set SDK_KEY to your LaunchDarkly SDK key first.");
      System.exit(1);
    }

    final LDClient client = new LDClient(SDK_KEY, config);
    if (client.isInitialized()) {
      showMessage("SDK successfully initialized!");
    } else {
      showMessage("SDK failed to initialize.  Please check your internet connection and SDK credential for any typo.");
      System.exit(1);
    }

    final LDContext context = LDContext.builder("example-user-key")
      .name("Sandy")
      .build();


    showMessage("Start evals.");
    Instant start = new Date().toInstant();
    for(int i = 0; i < 1000000; i++) {
      client.boolVariation(FEATURE_FLAG_KEY, context, false);
    }
    Instant end = new Date().toInstant();
    showMessage("End evals.");
    showMessage("Execution time: " + Duration.between(start, end));


    boolean flagValue = client.boolVariation(FEATURE_FLAG_KEY, context, false);
    showMessage("The '" + FEATURE_FLAG_KEY + "' feature flag evaluates to " + flagValue + ".");

    if (flagValue) {
      showBanner();
    }

    if(CIMode) {
      System.exit(0);
    }

    client.getFlagTracker().addFlagValueChangeListener(FEATURE_FLAG_KEY, context, event -> {
        showMessage("The '" + FEATURE_FLAG_KEY + "' feature flag evaluates to " + event.getNewValue() + ".");

        if (event.getNewValue().booleanValue()) {
          showBanner();
        }
    });
    showMessage("Listening for feature flag changes.");

    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      public void run() {
        try {
          client.close();
        } catch (IOException e) {
          // ignore
        }
      }
    }, "ldclient-cleanup-thread"));

    Object mon = new Object();
    synchronized (mon) {
      mon.wait();
    }
  }
}
