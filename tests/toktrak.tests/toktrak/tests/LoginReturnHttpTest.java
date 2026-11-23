package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Signing in returns to the page that asked for it; otherwise new users land on tracker setup.
final class LoginReturnHttpTest {
  @TempDir Path directory;

  @Test
  void given_signedOutDeepLink_when_signingIn_then_returnsToRequestedPageOnly() throws Exception {
    try (var usage = new UsageFixture(directory.resolve("return"))) {
      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      URI base = usage.base();

      HttpResponse<String> signedOut =
          get(client, base.resolve("/visualizations?range=custom&from=2026-07-01&to=2026-07-14"));
      assertEquals(302, signedOut.statusCode());
      String login = signedOut.headers().firstValue("Location").orElseThrow();
      assertEquals(
          "/login?return=%2Fvisualizations%3Frange%3Dcustom%26from%3D2026-07-01%26to%3D2026-07-14",
          login);

      HttpResponse<String> signedIn = get(client, base.resolve(login));
      assertEquals(302, signedIn.statusCode());
      assertEquals(
          base.resolve("/visualizations?range=custom&from=2026-07-01&to=2026-07-14").toString(),
          signedIn.headers().firstValue("Location").orElseThrow());

      // Unsafe deep links and non-page requests sign in without remembering a target.
      assertEquals(
          "/login",
          get(client, base.resolve("/visualizations?a=b:c"))
              .headers()
              .firstValue("Location")
              .orElseThrow());
      HttpResponse<String> post =
          client.send(
              HttpRequest.newBuilder(base.resolve("/tokens"))
                  .timeout(Duration.ofSeconds(3))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              BodyHandlers.ofString());
      assertEquals("/login", post.headers().firstValue("Location").orElseThrow());
      HttpResponse<String> background =
          client.send(
              HttpRequest.newBuilder(base.resolve("/visualizations"))
                  .timeout(Duration.ofSeconds(3))
                  .header("Datastar-Request", "true")
                  .GET()
                  .build(),
              BodyHandlers.ofString());
      assertEquals(401, background.statusCode());

      // The fixture's viewer has no tracker token yet, so unsafe or absent targets onboard.
      for (String unsafe :
          List.of(
              "",
              "?return=%2F%2Fevil.example",
              "?return=https%3A%2F%2Fevil.example%2F",
              "?return=%2Fvisualizations%5Cevil",
              "?return=%2Fadmin",
              "?return=%2Fvisualizations%3Fa%3D%3Cb%3E",
              "?return=%2Fvisualizations&return=%2Ftokens",
              "?next=%2Fvisualizations")) {
        assertEquals(
            base.resolve("/tokens").toString(),
            get(client, base.resolve("/login" + unsafe))
                .headers()
                .firstValue("Location")
                .orElseThrow(),
            unsafe);
      }

      usage.upload(UsageFixture.daily("2026-07-01", "1", "", ""));
      assertEquals(
          base.resolve("/").toString(),
          get(client, base.resolve("/login")).headers().firstValue("Location").orElseThrow());
    }
  }

  private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
        BodyHandlers.ofString());
  }
}
