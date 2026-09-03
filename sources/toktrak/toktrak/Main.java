package toktrak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import toktrak.http.Assets;
import toktrak.http.BaseView;
import toktrak.http.BaseView.CurrencySwitch;
import toktrak.http.BaseView.CurrentPage;
import toktrak.http.BaseView.RuntimeMode;
import toktrak.http.Changelog;
import toktrak.http.ChangesView;
import toktrak.http.ChangesViewRenderer;
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
      BuildInfo buildInfo = BuildInfo.from(System.getenv());
      var base =
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
              CurrencySwitch.disabled(),
              buildInfo.version());
      byte[] homeHtml;
      byte[] changesHtml;
      try {
        homeHtml =
            HttpSupport.renderEncoded(
                HomeViewRenderer.of(), new HomeView(base, SessionState.SIGNED_OUT));
        changesHtml =
            HttpSupport.renderEncoded(
                ChangesViewRenderer.of(),
                new ChangesView(base, Changelog.load(buildInfo.version())));
      } catch (IOException exception) {
        throw new IllegalStateException("production renderer self-check failed", exception);
      }
      String homeDocument = new String(homeHtml, StandardCharsets.UTF_8);
      String changesDocument = new String(changesHtml, StandardCharsets.UTF_8);
      if (!homeDocument.contains("class=\"home-brand\"")
          || !homeDocument.contains(logoLockupUrl)
          || !changesDocument.contains("class=\"current-release\"")
          || !changesDocument.contains(buildInfo.version())
          || homeDocument.contains("{{")
          || changesDocument.contains("{{")) {
        throw new IllegalStateException("production renderer self-check failed");
      }
      System.out.println(
          "TokTrak assets and rendering ok: "
              + assets.publicCount()
              + ", "
              + Math.addExact(homeHtml.length, changesHtml.length)
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
