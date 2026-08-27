package toktrak.fx;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import toktrak.http.HttpSupport;
import toktrak.json.Json;

public final class FxClient {
  private static final int RESPONSE_BYTES_MAX = 64 * 1024;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient client;
  private final URI uri;

  public FxClient(URI uri) {
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.uri = Objects.requireNonNull(uri, "uri");
  }

  public Rate fetch() {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<java.io.InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var body = response.body()) {
        if (response.statusCode() != 200) throw new IllegalStateException("FX request failed");
        return parse(HttpSupport.readLimited(body, RESPONSE_BYTES_MAX));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("FX request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("FX request interrupted", exception);
    }
  }

  static Rate parse(byte[] body) {
    Map<?, ?> document = Json.read(body, Map.class);
    Object date = document.get("date");
    Object ratesValue = document.get("rates");
    if (!(date instanceof String dateString) || !(ratesValue instanceof Map<?, ?> rates)) {
      throw new IllegalStateException("FX response is invalid");
    }
    Object value = rates.get("EUR");
    if (!(value instanceof Number number))
      throw new IllegalStateException("FX response is invalid");
    try {
      return new Rate(LocalDate.parse(dateString), new BigDecimal(number.toString()));
    } catch (DateTimeException | IllegalArgumentException exception) {
      throw new IllegalStateException("FX response is invalid", exception);
    }
  }

  public record Rate(LocalDate date, BigDecimal eurPerUsd) {
    public Rate {
      Objects.requireNonNull(date, "date");
      Objects.requireNonNull(eurPerUsd, "eurPerUsd");
      if (eurPerUsd.signum() <= 0 || eurPerUsd.compareTo(BigDecimal.TEN) > 0) {
        throw new IllegalArgumentException("FX rate is invalid");
      }
    }
  }
}
