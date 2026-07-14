package toktrak;

import java.util.concurrent.CountDownLatch;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    if (args == null) throw new IllegalArgumentException("args are required");
    requireAssertions();
    if (args.length == 1 && args[0].equals("--help")) {
      System.out.println("TokTrak dev server");
      return;
    }
    var app = App.start(args, System.getenv());
    var stopped = new CountDownLatch(1);
    var hook =
        Thread.ofPlatform()
            .name("toktrak-shutdown")
            .unstarted(
                () -> {
                  try {
                    app.close();
                  } finally {
                    stopped.countDown();
                  }
                });
    Runtime.getRuntime().addShutdownHook(hook);
    try {
      stopped.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      app.close();
    }
  }

  private static void requireAssertions() {
    boolean enabled = false;
    assert enabled = true;
    if (!enabled) throw new IllegalStateException("Java assertions must be enabled with -ea");
  }
}
