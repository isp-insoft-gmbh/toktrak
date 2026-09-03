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
    RuntimeMode runtimeMode,
    CurrentPage currentPage,
    CurrencySwitch currencyControl,
    String version) {
  public BaseView {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(stylesheetUrl, "stylesheetUrl");
    Objects.requireNonNull(datastarUrl, "datastarUrl");
    Objects.requireNonNull(faviconUrl, "faviconUrl");
    Objects.requireNonNull(logoWordmarkUrl, "logoWordmarkUrl");
    Objects.requireNonNull(logoWordmarkDarkUrl, "logoWordmarkDarkUrl");
    Objects.requireNonNull(logoLockupUrl, "logoLockupUrl");
    Objects.requireNonNull(logoLockupDarkUrl, "logoLockupDarkUrl");
    Objects.requireNonNull(runtimeMode, "runtimeMode");
    Objects.requireNonNull(currentPage, "currentPage");
    Objects.requireNonNull(currencyControl, "currencyControl");
    Objects.requireNonNull(version, "version");
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
    if (currencyControl.enabled() && currentPage == CurrentPage.NONE) {
      throw new IllegalArgumentException("currency switch is invalid");
    }
    if (!version.equals("dev")) {
      try {
        if (!version.matches("v(?:0|[1-9][0-9]{0,9})")) throw new NumberFormatException();
        Integer.parseInt(version.substring(1));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("version is invalid", exception);
      }
    }
  }

  public boolean development() {
    return runtimeMode == RuntimeMode.DEVELOPMENT;
  }

  public boolean navigation() {
    return currentPage != CurrentPage.NONE;
  }

  public boolean overviewCurrent() {
    return currentPage == CurrentPage.OVERVIEW;
  }

  public boolean visualizationsCurrent() {
    return currentPage == CurrentPage.VISUALIZATIONS;
  }

  public boolean scopeCurrent() {
    return currentPage == CurrentPage.SCOPE;
  }

  public boolean trackerCurrent() {
    return currentPage == CurrentPage.TRACKER;
  }

  public boolean currencySwitch() {
    return currencyControl.enabled();
  }

  public boolean usd() {
    return currencyControl.current() == Currency.USD;
  }

  public String currencySwitchUrl() {
    return currencyControl.url();
  }

  public String currencySwitchLabel() {
    return currencyControl.label();
  }

  private static void requireSvgAsset(String url, String name, String field) {
    assert url != null;
    assert name != null && !name.isBlank();
    assert field != null && !field.isBlank();
    if (!url.matches("/assets/" + name + "\\.[0-9a-f]{32}\\.svg")) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  public enum RuntimeMode {
    DEVELOPMENT,
    PRODUCTION
  }

  public enum CurrentPage {
    NONE,
    OVERVIEW,
    VISUALIZATIONS,
    SCOPE,
    TRACKER,
    CHANGES
  }

  public enum Currency {
    USD,
    EUR;

    static Currency from(DashboardCurrency currency) {
      assert currency != null;
      return valueOf(currency.name());
    }
  }

  public record CurrencySwitch(
      CurrencySwitchState state, Currency current, String url, String label) {
    public CurrencySwitch {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(current, "current");
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(label, "label");
      if (state == CurrencySwitchState.ENABLED) {
        if (!url.matches("/(?:visualizations|scope)?\\?currency=(?:USD|EUR)")
            || !label.matches("USD|EUR")
            || label.equals(current.name())) {
          throw new IllegalArgumentException("currency switch is invalid");
        }
      } else if (!url.isEmpty() || !label.isEmpty()) {
        throw new IllegalArgumentException("currency switch is disabled");
      }
    }

    public static CurrencySwitch enabled(Currency current, String url, Currency target) {
      Objects.requireNonNull(target, "target");
      return new CurrencySwitch(CurrencySwitchState.ENABLED, current, url, target.name());
    }

    public static CurrencySwitch disabled() {
      return new CurrencySwitch(CurrencySwitchState.DISABLED, Currency.USD, "", "");
    }

    public boolean enabled() {
      return state == CurrencySwitchState.ENABLED;
    }
  }

  public enum CurrencySwitchState {
    ENABLED,
    DISABLED
  }
}
