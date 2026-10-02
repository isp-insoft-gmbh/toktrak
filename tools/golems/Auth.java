import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/// Encrypts rotating Pi/Codex subscription files for ephemeral runner caches.
public final class Auth {
  private static final byte[] MAGIC = "TOKTRAK-GOLEM-AUTH".getBytes(StandardCharsets.US_ASCII);
  private static final int VERSION = 1;
  private static final int NONCE_LENGTH = 12;
  private static final int TAG_BITS = 128;
  private static final int FILE_MAX = 1024 * 1024;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Auth() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2
        || args.length > 3
        || !Set.of("seed", "encrypt", "decrypt").contains(args[0]))
      throw new IllegalArgumentException(
          "usage: java -ea tools/golems/Auth.java seed|encrypt|decrypt pi|codex [cache-file]");
    if (!args[1].equals("pi") && !args[1].equals("codex"))
      throw new IllegalArgumentException("provider must be pi or codex");
    var home = Path.of(System.getProperty("user.home"));
    var auth = home.resolve(args[1].equals("pi") ? ".pi/agent/auth.json" : ".codex/auth.json");
    var cache = args.length == 3 ? Path.of(args[2]) : null;
    if (args[0].equals("seed")) {
      if (cache != null) throw new IllegalArgumentException("seed does not accept a cache file");
      byte[] seed = secret("GOLEM_AUTH_SEED", FILE_MAX);
      try {
        restrictedWrite(auth, seed);
      } finally {
        Arrays.fill(seed, (byte) 0);
      }
      return;
    }
    if (cache == null) throw new IllegalArgumentException("cache file is required");
    byte[] key = secret("GOLEM_AUTH_CACHE_KEY", 32);
    if (key.length != 32)
      throw new IllegalArgumentException("GOLEM_AUTH_CACHE_KEY must be 32 bytes");
    try {
      if (args[0].equals("encrypt")) encrypt(args[1], auth, cache, key);
      else decrypt(args[1], auth, cache, key);
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  private static byte[] secret(String name, int max) {
    String text = System.getenv(name);
    if (text == null
        || text.length() > (max * 4L / 3 + 4)
        || !text.matches("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?"))
      throw new IllegalArgumentException(name + " must be bounded base64");
    byte[] bytes = Base64.getDecoder().decode(text);
    if (bytes.length == 0 || bytes.length > max) {
      Arrays.fill(bytes, (byte) 0);
      throw new IllegalArgumentException(name + " decoded length is invalid");
    }
    return bytes;
  }

  private static byte[] read(Path file, int max) throws Exception {
    if (Files.isSymbolicLink(file)
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) == 0
        || Files.size(file) > max)
      throw new IllegalArgumentException(
          "authentication file is missing, unsafe, or oversized: " + file);
    return Files.readAllBytes(file);
  }

  private static void encrypt(String provider, Path auth, Path cache, byte[] key) throws Exception {
    byte[] plain = read(auth, FILE_MAX);
    try {
      byte[] nonce = new byte[NONCE_LENGTH];
      RANDOM.nextBytes(nonce);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(provider.getBytes(StandardCharsets.US_ASCII));
      byte[] sealed = cipher.doFinal(plain);
      byte[] result = new byte[MAGIC.length + 1 + NONCE_LENGTH + sealed.length];
      System.arraycopy(MAGIC, 0, result, 0, MAGIC.length);
      result[MAGIC.length] = VERSION;
      System.arraycopy(nonce, 0, result, MAGIC.length + 1, NONCE_LENGTH);
      System.arraycopy(sealed, 0, result, MAGIC.length + 1 + NONCE_LENGTH, sealed.length);
      restrictedWrite(cache, result);
      Arrays.fill(sealed, (byte) 0);
      Arrays.fill(result, (byte) 0);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  private static void decrypt(String provider, Path auth, Path cache, byte[] key) throws Exception {
    byte[] stored = read(cache, FILE_MAX + 256);
    try {
      int prefix = MAGIC.length + 1 + NONCE_LENGTH;
      if (stored.length <= prefix + TAG_BITS / 8
          || !Arrays.equals(stored, 0, MAGIC.length, MAGIC, 0, MAGIC.length)
          || stored[MAGIC.length] != VERSION)
        throw new IllegalArgumentException("invalid authentication cache format");
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, stored, MAGIC.length + 1, NONCE_LENGTH));
      cipher.updateAAD(provider.getBytes(StandardCharsets.US_ASCII));
      byte[] plain = cipher.doFinal(stored, prefix, stored.length - prefix);
      try {
        if (plain.length == 0 || plain.length > FILE_MAX)
          throw new IllegalArgumentException("decrypted authentication length is invalid");
        restrictedWrite(auth, plain);
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    } finally {
      Arrays.fill(stored, (byte) 0);
    }
  }

  private static void restrictedWrite(Path path, byte[] data) throws Exception {
    var parent = path.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    if (Files.isSymbolicLink(parent)
        || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path))
      throw new IllegalArgumentException("authentication destination is unsafe: " + path);
    var temp = Files.createTempFile(parent, ".golem-auth-", ".tmp");
    try {
      if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(
            temp, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      Files.write(temp, data);
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
