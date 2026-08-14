package toktrak.http;

enum DashboardCurrency {
  USD("$"),
  EUR("€");

  private final String symbol;

  DashboardCurrency(String symbol) {
    this.symbol = symbol;
  }

  String symbol() {
    return symbol;
  }
}
