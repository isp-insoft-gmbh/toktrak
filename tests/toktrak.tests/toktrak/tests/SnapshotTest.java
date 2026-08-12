package toktrak.tests;

import static com.diffplug.selfie.Selfie.expectSelfie;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import toktrak.http.ErrorPage;

@Tag("snapshot")
final class SnapshotTest {
  @Test
  void given_productionErrorPage_when_renderingHtml_then_matchesApprovedDocument() {
    expectSelfie(
            ErrorPage.render(
                418,
                "teapot",
                "cannot brew coffee",
                "00000000-0000-4000-8000-000000000001",
                "POST",
                "/coffee",
                "/assets/main.0123456789abcdef0123456789abcdef.css",
                new IllegalStateException("debug failure"),
                false))
        .toMatchDisk();
  }
}
