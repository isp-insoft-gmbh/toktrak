package toktrak.http;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import toktrak.json.Json;

public final class TrackerScript {
  private static final int TEMPLATE_BYTES_MAX = 512 * 1024;
  private static final String BASE_URL_PLACEHOLDER = "__TOKTRAK_BASE_URL__";
  private static final String TOKEN_PLACEHOLDER = "__TOKTRAK_TOKEN__";

  private final String template;
  private final String baseUrl;

  public TrackerScript(byte[] template, URI baseUri) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(baseUri, "baseUri");
    if (template.length == 0 || template.length > TEMPLATE_BYTES_MAX) {
      throw new IllegalArgumentException("tracker template is invalid");
    }
    this.template = utf8(template);
    if (occurrences(this.template, BASE_URL_PLACEHOLDER) != 1
        || occurrences(this.template, TOKEN_PLACEHOLDER) != 1) {
      throw new IllegalArgumentException("tracker template placeholders are invalid");
    }
    String scheme = baseUri.getScheme();
    String path = baseUri.getPath();
    if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
        || baseUri.getHost() == null
        || baseUri.getUserInfo() != null
        || baseUri.getQuery() != null
        || baseUri.getFragment() != null
        || (path != null && !path.isEmpty() && !path.equals("/"))) {
      throw new IllegalArgumentException("tracker base URI is invalid");
    }
    String value = baseUri.toString();
    this.baseUrl = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  public Personalized render(String token) {
    Objects.requireNonNull(token, "token");
    if (!token.matches("tt_[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("tracker token is invalid");
    }
    String text =
        template
            .replace(BASE_URL_PLACEHOLDER, Json.write(baseUrl))
            .replace(TOKEN_PLACEHOLDER, Json.write(token));
    assert !text.contains(BASE_URL_PLACEHOLDER);
    assert !text.contains(TOKEN_PLACEHOLDER);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    assert bytes.length <= TEMPLATE_BYTES_MAX;
    return new Personalized(bytes, text, sha256(bytes));
  }

  private static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("tracker template is not valid UTF-8", exception);
    }
  }

  private static int occurrences(String value, String target) {
    assert value != null;
    assert target != null && !target.isEmpty();
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(target, offset)) >= 0) {
      count = Math.addExact(count, 1);
      offset = Math.addExact(offset, target.length());
    }
    return count;
  }

  private static String sha256(byte[] bytes) {
    assert bytes != null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static final class Personalized {
    private final byte[] bytes;
    private final String text;
    private final String sha256;

    private Personalized(byte[] bytes, String text, String sha256) {
      assert bytes != null;
      assert text != null;
      assert sha256 != null && sha256.matches("[0-9a-f]{64}");
      this.bytes = bytes.clone();
      this.text = text;
      this.sha256 = sha256;
    }

    public byte[] bytes() {
      return bytes.clone();
    }

    public String text() {
      return text;
    }

    public String sha256() {
      return sha256;
    }
  }
}
