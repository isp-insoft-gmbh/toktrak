package toktrak.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class Assets {
  private static final int ASSET_COUNT_MAX = 64;
  private static final int ASSET_BYTES_MAX = 4 * 1024 * 1024;
  private static final int ASSET_BYTES_TOTAL_MAX = 16 * 1024 * 1024;
  private static final int ASSET_INDEX_BYTES_MAX = 64 * 1024;
  private static final Pattern ASSET_PATH =
      Pattern.compile("[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern BYTE_LENGTH = Pattern.compile("0|[1-9][0-9]*");
  private static final Map<String, String> MEDIA_TYPES =
      Map.of(
          ".css", "text/css; charset=utf-8",
          ".js", "text/javascript; charset=utf-8",
          ".mjs", "text/javascript; charset=utf-8",
          ".svg", "image/svg+xml",
          ".webp", "image/webp",
          ".avif", "image/avif",
          ".woff2", "font/woff2");

  private final Map<String, String> publicUrls;
  private final Map<String, PublicAsset> publicAssets;
  private final Map<String, ImmutableBytes> privateAssets;

  private Assets(
      Map<String, String> publicUrls,
      Map<String, PublicAsset> publicAssets,
      Map<String, ImmutableBytes> privateAssets) {
    assert publicUrls != null;
    assert publicAssets != null;
    assert privateAssets != null;
    assert publicUrls.size() <= ASSET_COUNT_MAX;
    assert publicAssets.size() == publicUrls.size();
    assert privateAssets.size() <= ASSET_COUNT_MAX;
    this.publicUrls = Map.copyOf(publicUrls);
    this.publicAssets = Map.copyOf(publicAssets);
    this.privateAssets = Map.copyOf(privateAssets);
  }

  public static Assets load() {
    byte[] index = readModuleResource("assets/index.tsv", ASSET_INDEX_BYTES_MAX);
    return load(index, Assets::readModuleResource);
  }

  public static Assets loadForTest(byte[] index, Map<String, byte[]> resources) {
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(resources, "resources");
    return load(
        index.clone(),
        (name, bytesMax) -> {
          byte[] bytes = resources.get(name);
          if (bytes == null) throw missingResource(name);
          if (bytes.length > bytesMax) {
            throw new IllegalStateException(
                "packaged runtime asset exceeds indexed length: " + name);
          }
          return bytes.clone();
        });
  }

  public String publicUrl(String logicalName) {
    Objects.requireNonNull(logicalName, "logicalName");
    String url = publicUrls.get(logicalName);
    if (url == null) throw new IllegalArgumentException("runtime asset not found: " + logicalName);
    return url;
  }

  public byte[] privateBytes(String logicalName) {
    Objects.requireNonNull(logicalName, "logicalName");
    ImmutableBytes bytes = privateAssets.get(logicalName);
    if (bytes == null) {
      throw new IllegalArgumentException("runtime asset not found: " + logicalName);
    }
    return bytes.copy();
  }

  public int publicCount() {
    assert publicUrls.size() <= ASSET_COUNT_MAX;
    return publicUrls.size();
  }

  Optional<PublicAsset> publicAsset(String rawPath) {
    assert rawPath != null;
    return Optional.ofNullable(publicAssets.get(rawPath));
  }

  private static Assets load(byte[] index, ResourceReader resources) {
    assert index != null;
    assert resources != null;
    String text = parseIndexText(index);
    String[] lines = text.split("\n", -1);
    if (!lines[0].equals("toktrak-assets-v1")) {
      throw new IllegalStateException("invalid runtime asset index header");
    }
    int recordCount = lines.length - 2;
    if (recordCount < 0 || recordCount > ASSET_COUNT_MAX) {
      throw new IllegalStateException(
          "runtime asset index exceeds " + ASSET_COUNT_MAX + " entries");
    }

    var publicUrls = new HashMap<String, String>();
    var publicAssets = new HashMap<String, PublicAsset>();
    var privateAssets = new HashMap<String, ImmutableBytes>();
    Set<String> logicalNames = new HashSet<>();
    String previousKey = null;
    long totalBytes = 0;
    for (int indexLine = 1; indexLine <= recordCount; indexLine++) {
      String[] fields = lines[indexLine].split("\t", -1);
      if (fields.length != 5) {
        throw new IllegalStateException(
            "invalid runtime asset index fields at line " + (indexLine + 1));
      }
      String scope = fields[0];
      String logicalPath = fields[1];
      String lengthText = fields[2];
      String mediaType = fields[3];
      String hash = fields[4];
      if (!scope.equals("public") && !scope.equals("private")) {
        throw new IllegalStateException("invalid runtime asset scope: " + scope);
      }
      if (!ASSET_PATH.matcher(logicalPath).matches()) {
        throw new IllegalStateException("invalid runtime asset path: " + logicalPath);
      }
      String expectedMediaType = mediaType(logicalPath);
      if (!mediaType.equals(expectedMediaType)) {
        throw new IllegalStateException("invalid runtime asset media type: " + logicalPath);
      }
      if (!BYTE_LENGTH.matcher(lengthText).matches()) {
        throw new IllegalStateException("invalid runtime asset length: " + logicalPath);
      }
      int length;
      try {
        length = Integer.parseInt(lengthText);
      } catch (NumberFormatException exception) {
        throw new IllegalStateException("invalid runtime asset length: " + logicalPath, exception);
      }
      if (length > ASSET_BYTES_MAX) {
        throw new IllegalStateException(
            "runtime asset exceeds " + ASSET_BYTES_MAX + " bytes: " + logicalPath);
      }
      totalBytes = Math.addExact(totalBytes, length);
      if (totalBytes > ASSET_BYTES_TOTAL_MAX) {
        throw new IllegalStateException(
            "runtime assets exceed " + ASSET_BYTES_TOTAL_MAX + " total bytes");
      }
      if (!SHA256.matcher(hash).matches()) {
        throw new IllegalStateException("invalid runtime asset hash: " + logicalPath);
      }
      String key = scope + "\t" + logicalPath;
      if (previousKey != null && key.compareTo(previousKey) <= 0) {
        throw new IllegalStateException("unsorted or duplicate runtime asset: " + logicalPath);
      }
      previousKey = key;
      if (!logicalNames.add(logicalPath)) {
        throw new IllegalStateException("duplicate runtime asset logical name: " + logicalPath);
      }

      String resourceName = "assets/" + scope + "/" + logicalPath;
      byte[] bytes = resources.read(resourceName, length);
      if (bytes.length != length) {
        throw new IllegalStateException("packaged runtime asset has wrong size: " + resourceName);
      }
      byte[] expectedHash = HexFormat.of().parseHex(hash);
      if (!MessageDigest.isEqual(expectedHash, sha256(bytes))) {
        throw new IllegalStateException(
            "packaged runtime asset has wrong SHA-256: " + resourceName);
      }
      if (scope.equals("public")) {
        String url = publicUrl(logicalPath, hash);
        if (publicUrls.put(logicalPath, url) != null
            || publicAssets.put(url, new PublicAsset(mediaType, bytes)) != null) {
          throw new IllegalStateException("duplicate public runtime asset: " + logicalPath);
        }
      } else if (privateAssets.put(logicalPath, new ImmutableBytes(bytes)) != null) {
        throw new IllegalStateException("duplicate private runtime asset: " + logicalPath);
      }
    }
    assert publicUrls.size() + privateAssets.size() == recordCount;
    return new Assets(publicUrls, publicAssets, privateAssets);
  }

  private static String parseIndexText(byte[] index) {
    assert index != null;
    if (index.length > ASSET_INDEX_BYTES_MAX) {
      throw new IllegalStateException(
          "runtime asset index exceeds " + ASSET_INDEX_BYTES_MAX + " bytes");
    }
    if (index.length >= 3
        && index[0] == (byte) 0xEF
        && index[1] == (byte) 0xBB
        && index[2] == (byte) 0xBF) {
      throw new IllegalStateException("runtime asset index must not contain a BOM");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(index))
              .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException("runtime asset index is not valid UTF-8", exception);
    }
    if (text.indexOf('\r') >= 0) {
      throw new IllegalStateException("runtime asset index must use LF line endings");
    }
    if (!text.endsWith("\n")) {
      throw new IllegalStateException("runtime asset index must end with one newline");
    }
    return text;
  }

  private static String mediaType(String logicalPath) {
    assert logicalPath != null;
    for (Map.Entry<String, String> entry : MEDIA_TYPES.entrySet()) {
      if (logicalPath.endsWith(entry.getKey())) return entry.getValue();
    }
    throw new IllegalStateException("unsupported runtime asset type: " + logicalPath);
  }

  private static String publicUrl(String logicalPath, String hash) {
    assert logicalPath != null;
    assert hash != null && SHA256.matcher(hash).matches();
    int extension = logicalPath.lastIndexOf('.');
    assert extension > logicalPath.lastIndexOf('/');
    return "/assets/"
        + logicalPath.substring(0, extension)
        + "."
        + hash.substring(0, 32)
        + logicalPath.substring(extension);
  }

  private static byte[] readModuleResource(String resourceName, int bytesMax) {
    assert resourceName != null;
    assert bytesMax >= 0 && bytesMax <= ASSET_BYTES_MAX;
    try (InputStream input = Assets.class.getModule().getResourceAsStream(resourceName)) {
      if (input == null) throw missingResource(resourceName);
      byte[] bytes = input.readNBytes(bytesMax + 1);
      if (bytes.length > bytesMax) {
        throw new IllegalStateException(
            "packaged runtime asset exceeds indexed length: " + resourceName);
      }
      return bytes;
    } catch (IOException exception) {
      throw new IllegalStateException(
          "cannot read packaged runtime asset: " + resourceName, exception);
    }
  }

  private static IllegalStateException missingResource(String resourceName) {
    assert resourceName != null;
    return new IllegalStateException(
        "packaged runtime asset is missing: " + resourceName + "; run mise run clean and rebuild");
  }

  private static byte[] sha256(byte[] bytes) {
    assert bytes != null;
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  record PublicAsset(String mediaType, ImmutableBytes content) {
    PublicAsset(String mediaType, byte[] body) {
      this(mediaType, new ImmutableBytes(body));
    }

    PublicAsset {
      assert mediaType != null && !mediaType.isBlank();
      assert content != null;
    }

    byte[] body() {
      return content.value();
    }
  }

  static final class ImmutableBytes {
    private final byte[] value;

    private ImmutableBytes(byte[] value) {
      assert value != null;
      this.value = value.clone();
    }

    private byte[] copy() {
      return value.clone();
    }

    private byte[] value() {
      return value;
    }
  }

  @FunctionalInterface
  private interface ResourceReader {
    byte[] read(String resourceName, int bytesMax);
  }
}
