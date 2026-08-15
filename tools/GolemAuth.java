import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/// Encrypts rotating Pi and Codex subscription authentication for GitHub Actions caches.
///
/// Usage: `java -ea tools/GolemAuth.java seed <pi|codex>`
///
/// Usage: `java -ea tools/GolemAuth.java encrypt <pi|codex> <cache-file>`
///
/// Usage: `java -ea tools/GolemAuth.java decrypt <pi|codex> <cache-file>`
///
/// `GOLEM_AUTH_SEED` supplies base64 authentication for `seed`. `GOLEM_AUTH_CACHE_KEY` supplies one
/// base64 AES-256 key for `encrypt` and `decrypt`.
public final class GolemAuth {
  private static final byte[] MAGIC = "TOKTRAK-GOLEM-AUTH".getBytes(StandardCharsets.US_ASCII);
  private static final byte VERSION = 1;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int AUTH_BYTES_MAX = 1024 * 1024;
  private static final int CACHE_BYTES_MAX = AUTH_BYTES_MAX + 256;
  private static final Set<PosixFilePermission> OWNER_READ_WRITE =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private GolemAuth() {}

  public static void main(String[] arguments) {
    try {
      run(arguments);
    } catch (Exception exception) {
      System.err.println(exception.getMessage());
      System.exit(1);
    }
  }

  private static void run(String[] arguments) throws Exception {
    if (arguments.length == 2 && arguments[0].equals("seed")) {
      seed(arguments[1]);
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("encrypt")) {
      encrypt(arguments[1], Path.of(arguments[2]));
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("decrypt")) {
      decrypt(arguments[1], Path.of(arguments[2]));
      return;
    }
    throw new IllegalArgumentException(
        "usage: GolemAuth seed <pi|codex> | encrypt <pi|codex> <cache-file> | decrypt <pi|codex>"
            + " <cache-file>");
  }

  private static void seed(String provider) throws IOException {
    byte[] authentication = decodeSeed(requiredEnvironment("GOLEM_AUTH_SEED"));
    try {
      writeRestricted(authenticationPath(provider), authentication);
    } finally {
      Arrays.fill(authentication, (byte) 0);
    }
  }

  private static void encrypt(String provider, Path cacheFile)
      throws IOException, GeneralSecurityException {
    byte[] authentication = readRegular(authenticationPath(provider), AUTH_BYTES_MAX);
    byte[] key = decodeKey();
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      new SecureRandom().nextBytes(nonce);
      Cipher cipher = cipher(Cipher.ENCRYPT_MODE, provider, key, nonce);
      byte[] sealed = cipher.doFinal(authentication);
      ByteBuffer cache = ByteBuffer.allocate(MAGIC.length + 1 + NONCE_BYTES + sealed.length);
      cache.put(MAGIC).put(VERSION).put(nonce).put(sealed);
      writeRestricted(cacheFile, cache.array());
      Arrays.fill(sealed, (byte) 0);
    } finally {
      Arrays.fill(authentication, (byte) 0);
      Arrays.fill(key, (byte) 0);
    }
  }

  private static void decrypt(String provider, Path cacheFile)
      throws IOException, GeneralSecurityException {
    byte[] cache = readRegular(cacheFile, CACHE_BYTES_MAX);
    int headerBytes = MAGIC.length + 1 + NONCE_BYTES;
    if (cache.length <= headerBytes) {
      throw new IllegalArgumentException("authentication cache is truncated");
    }
    ByteBuffer buffer = ByteBuffer.wrap(cache);
    byte[] magic = new byte[MAGIC.length];
    buffer.get(magic);
    if (!Arrays.equals(magic, MAGIC) || buffer.get() != VERSION) {
      throw new IllegalArgumentException("authentication cache format is invalid");
    }
    byte[] nonce = new byte[NONCE_BYTES];
    buffer.get(nonce);
    byte[] sealed = new byte[buffer.remaining()];
    buffer.get(sealed);
    byte[] key = decodeKey();
    byte[] authentication = null;
    try {
      authentication = cipher(Cipher.DECRYPT_MODE, provider, key, nonce).doFinal(sealed);
      if (authentication.length == 0 || authentication.length > AUTH_BYTES_MAX) {
        throw new IllegalArgumentException("decrypted authentication size is invalid");
      }
      writeRestricted(authenticationPath(provider), authentication);
    } finally {
      Arrays.fill(cache, (byte) 0);
      Arrays.fill(sealed, (byte) 0);
      Arrays.fill(key, (byte) 0);
      if (authentication != null) {
        Arrays.fill(authentication, (byte) 0);
      }
    }
  }

  private static Cipher cipher(int mode, String provider, byte[] key, byte[] nonce)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(provider.getBytes(StandardCharsets.US_ASCII));
    return cipher;
  }

  private static Path authenticationPath(String provider) {
    Path home = Path.of(System.getProperty("user.home"));
    return switch (provider) {
      case "pi" -> home.resolve(".pi/agent/auth.json");
      case "codex" -> home.resolve(".codex/auth.json");
      default -> throw new IllegalArgumentException("provider must be pi or codex");
    };
  }

  private static byte[] decodeSeed(String encoded) {
    if (encoded.length() > (AUTH_BYTES_MAX * 4 / 3) + 4) {
      throw new IllegalArgumentException("GOLEM_AUTH_SEED exceeds the authentication size limit");
    }
    byte[] authentication;
    try {
      authentication = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("GOLEM_AUTH_SEED must be base64", exception);
    }
    if (authentication.length == 0 || authentication.length > AUTH_BYTES_MAX) {
      Arrays.fill(authentication, (byte) 0);
      throw new IllegalArgumentException("GOLEM_AUTH_SEED decoded size is invalid");
    }
    return authentication;
  }

  private static byte[] decodeKey() {
    String encoded = requiredEnvironment("GOLEM_AUTH_CACHE_KEY");
    if (encoded.length() > 128) {
      throw new IllegalArgumentException("GOLEM_AUTH_CACHE_KEY must encode exactly 32 bytes");
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("GOLEM_AUTH_CACHE_KEY must be base64", exception);
    }
    if (key.length != 32) {
      Arrays.fill(key, (byte) 0);
      throw new IllegalArgumentException("GOLEM_AUTH_CACHE_KEY must encode exactly 32 bytes");
    }
    return key;
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing " + name);
    }
    return value.trim();
  }

  private static byte[] readRegular(Path path, int bytesMax) throws IOException {
    var attributes =
        Files.readAttributes(
            path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
      throw new IllegalArgumentException(path + " must be a regular non-symbolic file");
    }
    if (attributes.size() <= 0 || attributes.size() > bytesMax) {
      throw new IllegalArgumentException(path + " violates the " + bytesMax + " byte size limit");
    }
    return Files.readAllBytes(path);
  }

  private static void writeRestricted(Path path, byte[] content) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null) {
      throw new IllegalArgumentException(path + " has no parent directory");
    }
    Files.createDirectories(parent);
    if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(parent + " must be a regular non-symbolic directory");
    }
    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
        && (Files.isSymbolicLink(absolute)
            || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))) {
      throw new IllegalArgumentException(absolute + " must be a regular non-symbolic file");
    }
    Path temporary = Files.createTempFile(parent, ".golem-auth-", ".tmp");
    try {
      restrict(temporary);
      Files.write(temporary, content);
      try {
        Files.move(
            temporary,
            absolute,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
      restrict(absolute);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void restrict(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
    } catch (UnsupportedOperationException ignored) {
      // The Windows user profile ACL is the trust boundary.
    }
  }
}
