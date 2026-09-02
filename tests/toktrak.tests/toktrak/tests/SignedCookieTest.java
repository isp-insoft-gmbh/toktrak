package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import toktrak.auth.SignedCookie;

final class SignedCookieTest {
  private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
  private static final Instant EXPIRY = NOW.plusSeconds(60);

  @Test
  void given_fieldsAtDocumentedLimits_when_roundTripping_then_acceptsEveryExactLimit() {
    var cookies = new SignedCookie(new byte[32]);
    String value = "v".repeat(2_048);
    assertEquals(
        Map.of("kind", value), cookies.verify(cookies.sign(Map.of("kind", value), EXPIRY), NOW));
    var fields = new HashMap<String, String>();
    for (int index = 0; index < 15; index++) fields.put("field" + index, "value");
    assertEquals(Map.copyOf(fields), cookies.verify(cookies.sign(fields, EXPIRY), NOW));
  }

  @Test
  void given_cookieAtCharacterLimit_when_roundTripping_then_acceptsLimitAndRejectsOneMore() {
    var cookies = new SignedCookie(new byte[32]);
    var fields = Map.of("a", "v".repeat(2_048), "b", "v".repeat(2_048), "c", "v".repeat(1_992));
    String cookie = cookies.sign(fields, EXPIRY);
    assertEquals(8 * 1024, cookie.length());
    assertEquals(fields, cookies.verify(cookie, NOW));
    var overflowing =
        Map.of("a", "v".repeat(2_048), "b", "v".repeat(2_048), "c", "v".repeat(1_993));
    assertThrows(IllegalArgumentException.class, () -> cookies.sign(overflowing, EXPIRY));
  }

  @Test
  void given_signedEmptyOrNamelessPayloads_when_verifying_then_rejectsBothAsInvalid()
      throws Exception {
    var cookies = new SignedCookie(new byte[32]);
    var emptyPayload =
        assertThrows(
            IllegalArgumentException.class, () -> cookies.verify("." + signature(""), NOW));
    assertEquals("cookie is invalid", emptyPayload.getMessage());
    String namelessField = forge("=value&exp=" + EXPIRY.getEpochSecond());
    var namelessFieldRejection =
        assertThrows(IllegalArgumentException.class, () -> cookies.verify(namelessField, NOW));
    assertEquals("cookie is invalid", namelessFieldRejection.getMessage());
  }

  private static String forge(String payload) throws Exception {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + signature(encoded);
  }

  private static String signature(String payload) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
  }
}
