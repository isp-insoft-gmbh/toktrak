package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toktrak.http.Assets;

final class AssetsTest {
  private static final String CSS_MEDIA_TYPE = "text/css; charset=utf-8";

  @Test
  void given_packagedAssets_when_loadingModule_then_verifiesAndIndexesAssets() throws Exception {
    Assets assets = Assets.load();

    String url = assets.publicUrl("main.css");
    assertTrue(url.matches("/assets/main\\.[0-9a-f]{32}\\.css"), url);
    byte[] expected = Files.readAllBytes(Path.of("sources/toktrak/assets/public/main.css"));
    assertEquals("/assets/main." + sha256(expected).substring(0, 32) + ".css", url);
    Assets inMemory =
        Assets.loadForTest(
            index("public", "main.css", CSS_MEDIA_TYPE, expected),
            Map.of("assets/public/main.css", expected));
    assertEquals(url, inMemory.publicUrl("main.css"));
  }

  @Test
  void given_corruptIndex_when_loadingAssets_then_rejectsIndex() throws Exception {
    byte[] css = "body{}".getBytes(StandardCharsets.UTF_8);
    String hash = sha256(css);
    String record = "public\tmain.css\t6\t" + CSS_MEDIA_TYPE + "\t" + hash + "\n";
    Map<String, byte[]> resources = Map.of("assets/public/main.css", css);

    assertCorrupt("wrong\n" + record, resources, "invalid runtime asset index header");
    assertCorrupt(
        "\ufefftoktrak-assets-v1\n" + record,
        resources,
        "runtime asset index must not contain a BOM");
    assertCorrupt(
        ("toktrak-assets-v1\n" + record).replace("\n", "\r\n"),
        resources,
        "runtime asset index must use LF line endings");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.stripTrailing(),
        resources,
        "runtime asset index must end with one newline");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.stripTrailing() + "\textra\n",
        resources,
        "invalid runtime asset index fields at line 2");
    String second = "public\tz.css\t6\t" + CSS_MEDIA_TYPE + "\t" + hash + "\n";
    assertCorrupt(
        "toktrak-assets-v1\n" + second + record,
        Map.of("assets/public/main.css", css, "assets/public/z.css", css),
        "unsorted or duplicate runtime asset: main.css");
    assertCorrupt(
        "toktrak-assets-v1\n" + record + record,
        resources,
        "unsorted or duplicate runtime asset: main.css");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.replace("\t6\t", "\t06\t"),
        resources,
        "invalid runtime asset length: main.css");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.replace(hash, hash.toUpperCase(Locale.ROOT)),
        resources,
        "invalid runtime asset hash: main.css");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.replace("public", "shared"),
        resources,
        "invalid runtime asset scope: shared");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.replace("main.css", "Bad.css"),
        resources,
        "invalid runtime asset path: Bad.css");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.replace(CSS_MEDIA_TYPE, "application/octet-stream"),
        resources,
        "invalid runtime asset media type: main.css");
    assertCorrupt(
        "toktrak-assets-v1\n" + record.replace("\t6\t", "\t4194305\t"),
        resources,
        "runtime asset exceeds 4194304 bytes: main.css");
  }

  @Test
  void given_missingOrChangedResource_when_loadingAssets_then_rejectsResource() throws Exception {
    byte[] css = "body{}".getBytes(StandardCharsets.UTF_8);
    byte[] index = index("public", "main.css", CSS_MEDIA_TYPE, css);

    IllegalStateException missing =
        assertThrows(IllegalStateException.class, () -> Assets.loadForTest(index, Map.of()));
    assertTrue(missing.getMessage().contains("assets/public/main.css"));
    assertTrue(missing.getMessage().contains("mise run clean"));
    assertThrows(
        IllegalStateException.class,
        () ->
            Assets.loadForTest(
                index,
                Map.of("assets/public/main.css", "changed".getBytes(StandardCharsets.UTF_8))));
    assertThrows(
        IllegalStateException.class,
        () ->
            Assets.loadForTest(
                index,
                Map.of("assets/public/main.css", "bodz{}".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void given_privateAsset_when_loadingAssets_then_exposesNoPublicUrl() throws Exception {
    byte[] template = "export{};".getBytes(StandardCharsets.UTF_8);
    Assets assets =
        Assets.loadForTest(
            index("private", "tracker.mjs", "text/javascript; charset=utf-8", template),
            Map.of("assets/private/tracker.mjs", template));

    byte[] first = assets.privateBytes("tracker.mjs");
    assertArrayEquals(template, first);
    first[0] = 0;
    assertArrayEquals(template, assets.privateBytes("tracker.mjs"));
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> assets.publicUrl("tracker.mjs"));
    assertEquals("runtime asset not found: tracker.mjs", exception.getMessage());
  }

  @Test
  void given_staleFingerprint_when_lookingUpPublicAsset_then_returnsEmpty() throws Exception {
    byte[] css = "body{}".getBytes(StandardCharsets.UTF_8);
    Assets assets =
        Assets.loadForTest(
            index("public", "main.css", CSS_MEDIA_TYPE, css),
            Map.of("assets/public/main.css", css));
    String url = assets.publicUrl("main.css");
    int fingerprintStart = "/assets/main.".length();
    char replacement = url.charAt(fingerprintStart) == '0' ? '1' : '0';
    String stale =
        url.substring(0, fingerprintStart) + replacement + url.substring(fingerprintStart + 1);

    String staleLogicalName = stale.substring("/assets/".length());
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> assets.publicUrl(staleLogicalName));
    assertEquals("runtime asset not found: " + staleLogicalName, exception.getMessage());
  }

  private static byte[] index(String scope, String path, String mediaType, byte[] bytes)
      throws Exception {
    return ("toktrak-assets-v1\n"
            + scope
            + "\t"
            + path
            + "\t"
            + bytes.length
            + "\t"
            + mediaType
            + "\t"
            + sha256(bytes)
            + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void assertCorrupt(
      String index, Map<String, byte[]> resources, String expectedMessage) {
    Map<String, byte[]> defensiveResources = new LinkedHashMap<>(resources);
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> Assets.loadForTest(index.getBytes(StandardCharsets.UTF_8), defensiveResources));
    assertEquals(expectedMessage, exception.getMessage());
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
