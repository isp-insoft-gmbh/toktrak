package toktrak.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
    String css = new String(expected, StandardCharsets.UTF_8);
    assertTrue(css.contains("@media (prefers-reduced-motion: reduce)"));
    assertTrue(css.contains("@media (max-width: 44rem)"));
    assertTrue(css.contains(":focus-visible"));
    assertEquals("/assets/main." + sha256(expected).substring(0, 32) + ".css", url);
    byte[] datastar = Files.readAllBytes(Path.of("sources/toktrak/assets/public/datastar.js"));
    assertEquals(
        "/assets/datastar." + sha256(datastar).substring(0, 32) + ".js",
        assets.publicUrl("datastar.js"));
    assertTrue(new String(datastar, StandardCharsets.UTF_8).startsWith("// Datastar v1.0.2\n"));
    byte[] clipboard = Files.readAllBytes(Path.of("sources/toktrak/assets/public/clipboard.js"));
    assertEquals(
        "/assets/clipboard." + sha256(clipboard).substring(0, 32) + ".js",
        assets.publicUrl("clipboard.js"));
    assertTrue(new String(clipboard, StandardCharsets.UTF_8).contains("navigator.clipboard"));
    for (String name :
        List.of(
            "favicon.svg",
            "logo-lockup-dark.svg",
            "logo-lockup.svg",
            "logo-mark.svg",
            "logo-wordmark-dark.svg",
            "logo-wordmark.svg")) {
      String assetUrl = assets.publicUrl(name);
      assertTrue(assetUrl.matches("/assets/" + name.replace(".", "\\.[0-9a-f]{32}\\.")), assetUrl);
      String svg = Files.readString(Path.of("sources/toktrak/assets/public", name));
      assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""), name);
      assertTrue(svg.contains("<title"), name);
      var colors = Pattern.compile("#[0-9a-fA-F]{6}").matcher(svg);
      while (colors.find()) {
        assertTrue(
            Set.of("#0b0909", "#2e4540", "#408175", "#b5b9f0")
                .contains(colors.group().toLowerCase(Locale.ROOT)),
            name + ": " + colors.group());
      }
    }
    assertEquals(9, assets.publicCount());
    Assets inMemory =
        Assets.loadForTest(
            index("public", "main.css", CSS_MEDIA_TYPE, expected),
            Map.of("assets/public/main.css", expected));
    assertEquals(url, inMemory.publicUrl("main.css"));
  }

  @Test
  void given_toktrakTheme_when_readingCss_then_limitsLiteralsToThemeAndSemanticBases()
      throws Exception {
    String css = Files.readString(Path.of("sources/toktrak/assets/public/main.css"));
    var colors = new HashSet<String>();
    var matcher = Pattern.compile("#[0-9a-fA-F]{6}").matcher(css);
    while (matcher.find()) colors.add(matcher.group().toLowerCase(Locale.ROOT));

    assertEquals(
        Set.of(
            "#0b0909", "#2e4540", "#408175", "#b5b9f0", "#c52f4f", "#c88900", "#2f6fa8", "#2f7d56"),
        colors);
    assertTrue(css.contains("--background: oklch(from var(--theme-lilac)"));
    assertTrue(css.contains("--danger-surface: oklch(from var(--semantic-danger)"));
  }

  @Test
  void given_responsiveUiStyles_when_readingCss_then_keepsNavigationAndTrackerControlsUsable()
      throws Exception {
    String css = Files.readString(Path.of("sources/toktrak/assets/public/main.css"));

    assertTrue(
        css.matches(
            "(?s).*\\.site-header nav \\{[^}]*display: grid;[^}]*grid-template-columns: repeat\\(2,"
                + " minmax\\(0, 1fr\\)\\);[^}]*\\}.*"));
    assertFalse(css.matches("(?s).*\\.site-header nav \\{[^}]*overflow-x: auto;[^}]*\\}.*"));
    assertTrue(
        css.matches(
            "(?s).*\\.token-list > li \\{[^}]*grid-template-columns: minmax\\(0, 1fr\\)"
                + " auto;[^}]*\\}.*"));
    assertTrue(
        css.matches(
            "(?s).*\\.token-list > li,\\s*\\.tracker-form-row,\\s*\\.token-copy"
                + " \\{[^}]*grid-template-columns: 1fr;[^}]*\\}.*"));
    assertTrue(css.matches("(?s).*\\.error-page \\{[^}]*width: calc\\(100% - 2rem\\);[^}]*\\}.*"));
    assertTrue(css.contains(".viz-section > .table-wrap {\n  grid-column: 2 / -1;"));
    assertTrue(css.contains(".scope-page:not(.tracker-page) > section {\n  display: grid;"));
    assertTrue(
        css.matches(
            "(?s).*@media \\(max-width: 44rem\\) \\{.*\\.streams li \\{[^}]*grid-template-columns:"
                + " 2.5rem minmax\\(0, 1fr\\);[^}]*\\}.*"));
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
    assertCorrupt(
        "toktrak-assets-v1\n" + record.replace("main.css", "main.exe"),
        resources,
        "unsupported runtime asset type: main.exe");
  }

  @Test
  void given_invalidIndexEncodingAndSize_when_loadingAssets_then_rejectsIndex() {
    assertThrows(
        IllegalStateException.class, () -> Assets.loadForTest(new byte[] {(byte) 0xC3}, Map.of()));
    assertThrows(
        IllegalStateException.class, () -> Assets.loadForTest(new byte[1024 * 1024 + 1], Map.of()));
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
