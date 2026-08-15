package toktrak.http;

import java.util.Objects;

public record BaseView(
    String title,
    String stylesheetUrl,
    String datastarUrl,
    String faviconUrl,
    String logoWordmarkUrl,
    String logoWordmarkDarkUrl,
    String logoLockupUrl,
    String logoLockupDarkUrl,
    boolean development,
    boolean navigation,
    boolean overviewCurrent,
    boolean visualizationsCurrent,
    boolean scopeCurrent,
    boolean trackerCurrent,
    boolean currencySwitch,
    boolean usd,
    String currencySwitchUrl,
    String currencySwitchLabel) {
  public BaseView {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(stylesheetUrl, "stylesheetUrl");
    Objects.requireNonNull(datastarUrl, "datastarUrl");
    Objects.requireNonNull(faviconUrl, "faviconUrl");
    Objects.requireNonNull(logoWordmarkUrl, "logoWordmarkUrl");
    Objects.requireNonNull(logoWordmarkDarkUrl, "logoWordmarkDarkUrl");
    Objects.requireNonNull(logoLockupUrl, "logoLockupUrl");
    Objects.requireNonNull(logoLockupDarkUrl, "logoLockupDarkUrl");
    Objects.requireNonNull(currencySwitchUrl, "currencySwitchUrl");
    Objects.requireNonNull(currencySwitchLabel, "currencySwitchLabel");
    if (title.isBlank() || title.length() > 128) {
      throw new IllegalArgumentException("title is invalid");
    }
    if (!stylesheetUrl.matches("/assets/main\\.[0-9a-f]{32}\\.css")) {
      throw new IllegalArgumentException("stylesheetUrl is invalid");
    }
    if (!datastarUrl.matches("/assets/datastar\\.[0-9a-f]{32}\\.js")) {
      throw new IllegalArgumentException("datastarUrl is invalid");
    }
    requireSvgAsset(faviconUrl, "favicon", "faviconUrl");
    requireSvgAsset(logoWordmarkUrl, "logo-wordmark", "logoWordmarkUrl");
    requireSvgAsset(logoWordmarkDarkUrl, "logo-wordmark-dark", "logoWordmarkDarkUrl");
    requireSvgAsset(logoLockupUrl, "logo-lockup", "logoLockupUrl");
    requireSvgAsset(logoLockupDarkUrl, "logo-lockup-dark", "logoLockupDarkUrl");
    int currentPages =
        (overviewCurrent ? 1 : 0)
            + (visualizationsCurrent ? 1 : 0)
            + (scopeCurrent ? 1 : 0)
            + (trackerCurrent ? 1 : 0);
    if (currentPages != (navigation ? 1 : 0)) {
      throw new IllegalArgumentException("navigation state is invalid");
    }
    if (currencySwitch) {
      if (!navigation
          || !currencySwitchUrl.matches("/(?:visualizations|scope)?\\?currency=(?:USD|EUR)")
          || !currencySwitchLabel.matches("USD|EUR")) {
        throw new IllegalArgumentException("currency switch is invalid");
      }
    } else if (!currencySwitchUrl.isEmpty() || !currencySwitchLabel.isEmpty()) {
      throw new IllegalArgumentException("currency switch is disabled");
    }
  }

  private static void requireSvgAsset(String url, String name, String field) {
    assert url != null;
    assert name != null && !name.isBlank();
    assert field != null && !field.isBlank();
    if (!url.matches("/assets/" + name + "\\.[0-9a-f]{32}\\.svg")) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }
}
