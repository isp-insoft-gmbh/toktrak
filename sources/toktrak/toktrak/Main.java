package toktrak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import toktrak.http.Assets;
import toktrak.http.BaseView;
import toktrak.http.BaseView.CurrencySwitch;
import toktrak.http.BaseView.CurrentPage;
import toktrak.http.BaseView.RuntimeMode;
import toktrak.http.HomeView;
import toktrak.http.HomeView.SessionState;
import toktrak.http.HomeViewRenderer;
import toktrak.http.HttpSupport;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (args == null) throw new IllegalArgumentException("args are required");
    if (!Main.class.desiredAssertionStatus()) {
      throw new IllegalStateException("Java assertions must be enabled with -ea");
    }
    if (args.length == 1 && args[0].equals("--help")) {
      System.out.println("TokTrak server");
      return;
    }
    if (args.length == 1 && args[0].equals("--check-assets")) {
      Assets assets = Assets.load();
      String stylesheetUrl = assets.publicUrl("main.css");
      String datastarUrl = assets.publicUrl("datastar.js");
      String faviconUrl = assets.publicUrl("favicon.svg");
      String logoWordmarkUrl = assets.publicUrl("logo-wordmark.svg");
      String logoWordmarkDarkUrl = assets.publicUrl("logo-wordmark-dark.svg");
      String logoLockupUrl = assets.publicUrl("logo-lockup.svg");
      String logoLockupDarkUrl = assets.publicUrl("logo-lockup-dark.svg");
      byte[] html;
      try {
        html =
            HttpSupport.renderEncoded(
                HomeViewRenderer.of(),
                new HomeView(
                    new BaseView(
                        "TokTrak",
                        stylesheetUrl,
                        datastarUrl,
                        faviconUrl,
                        logoWordmarkUrl,
                        logoWordmarkDarkUrl,
                        logoLockupUrl,
                        logoLockupDarkUrl,
                        RuntimeMode.PRODUCTION,
                        CurrentPage.NONE,
                        CurrencySwitch.disabled()),
                    SessionState.SIGNED_OUT));
      } catch (IOException exception) {
        throw new IllegalStateException("production renderer self-check failed", exception);
      }
      String document = new String(html, StandardCharsets.UTF_8);
      if (!document.contains("class=\"home-brand\"")
          || !document.contains(logoLockupUrl)
          || document.contains("{{")) {
        throw new IllegalStateException("production renderer self-check failed");
      }
      System.out.println(
          "TokTrak assets and rendering ok: "
              + assets.publicCount()
              + ", "
              + html.length
              + " bytes");
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
}
