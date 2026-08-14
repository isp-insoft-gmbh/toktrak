package toktrak.http;

import java.util.Objects;

public record BaseView(
    String title,
    String stylesheetUrl,
    String datastarUrl,
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
}
