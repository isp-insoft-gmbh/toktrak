package tools;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

public final class BuildTest {
  private static final int DECLARED_METHOD_COUNT_MAX = 1_000;
  private static final Pattern TEST_NAME =
      Pattern.compile("given_[a-z][A-Za-z0-9]*_when_[a-z][A-Za-z0-9]*_then_[a-z][A-Za-z0-9]*");

  private BuildTest() {}

  public static void main(String[] args) throws Exception {
    requireAssertions(BuildTest.class);
    if (args.length == 1) {
      if (args[0].equals("done")) return;
      if (args[0].equals("sleep")) {
        Thread.sleep(Duration.ofMinutes(1));
        return;
      }
      if (args[0].equals("spawn")) {
        child("sleep").waitFor();
        return;
      }
      throw new IllegalArgumentException("unexpected argument: " + args[0]);
    }
    if (args.length == 4 && args[0].equals("fake-tool")) {
      Files.writeString(
          Path.of(args[1]),
          args[2] + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      System.out.print(args[2] + "-output");
      switch (args[3]) {
        case "ok" -> {}
        case "fail" -> throw new IllegalStateException("controlled failure");
        case "oversized" -> System.out.write(new byte[1024 * 1024 + 1]);
        case "malformed" -> System.out.write(0xC3);
        default -> throw new IllegalArgumentException("unexpected fake-tool result: " + args[3]);
      }
      System.out.flush();
      return;
    }
    if (args.length != 0) throw new IllegalArgumentException("unexpected arguments");
    requireTestNames(BuildTest.class.getDeclaredMethods());
    given_repositoryAttributes_when_readingTextPolicy_then_forcesLfAndPinsCorpusBytes();
    given_oversizedFile_when_hashing_then_rejectsInput();
    given_oversizedTree_when_listingPaths_then_rejectsInput();
    given_invalidArguments_when_writingArgumentFile_then_rejectsInput();
    given_templateInputs_when_validating_then_acceptsOnlyBoundedCanonicalUtf8();
    given_projectSources_when_generatingEclipseProjects_then_writesValidMetadata();
    given_projectSources_when_generatingIntellijProjects_then_writesValidMetadata();
    given_testPaths_when_selectingTests_then_returnsExpectedClasses();
    given_focusedTestArguments_when_parsing_then_requiresExplicitOnlyFlag();
    given_miseTestArguments_when_decoding_then_preservesPathsAndRejectsMalformedValues();
    given_javascriptTestPaths_when_selecting_then_runsOnlyRequestedFiles();
    given_interactiveSnapshotEnvironment_when_enforcingReadonly_then_overridesBothNames();
    given_productionPath_when_selectingTests_then_rejectsInput();
    given_symbolicSourceAncestor_when_selectingPitTargets_then_rejectsInput();
    given_pitArguments_when_selectingTargets_then_parsesOptions();
    given_invalidPitArguments_when_selectingTargets_then_rejectsInput();
    given_pitSelection_when_buildingArguments_then_preservesRequiredOptions();
    given_coverageCounts_when_checkingMinimum_then_acceptsBoundaryAndRejectsBelow();
    given_pitReports_when_validatingReport_then_acceptsOnlyValidMutations();
    given_pitArtifactSets_when_validatingDependencies_then_acceptsCompleteSetAndRejectsMissingHistoryOrInvalidJunitPlugin();
    given_existingArgumentFile_when_requestingPitHelp_then_preservesFile();
    given_completeArtifact_when_checkingInventory_then_rejectsCorruption();
    given_malformedArtifactMarkers_when_checkingInventory_then_rejectsMarker();
    given_artifactFailpoints_when_rebuilding_then_nextRunPublishesCompleteTree();
    given_generatedSymbolicLinks_when_checkingInventory_then_preservesInternalTargetsAndRejectsEscapes();
    given_outputArtifacts_when_cleaning_then_deletesEverythingExceptHeldLock();
    given_runningBuildCommand_when_acquiringBuildLock_then_rejectsCommand();
    given_runningDevelopmentServer_when_requiringExclusiveBuild_then_rejectsCommand();
    given_validRuntimeAssets_when_buildingBundle_then_returnsCanonicalIndex();
    given_svgSecurityInputs_when_buildingBundle_then_preservesValidationBoundary();
    given_invalidRuntimeAssets_when_buildingBundle_then_returnsActionableErrors();
    given_runtimeAssetBounds_when_buildingBundle_then_rejectsExcess();
    given_runtimeAssetBundle_when_writingModule_then_copiesAndVerifiesResources();
    given_runtimeAssets_when_fingerprintingCompilation_then_changesFingerprint();
    given_templateChange_when_fingerprintingCompilation_then_changesFingerprint();
    given_explodedAssets_when_fingerprintingRuntime_then_changesFingerprint();
    given_completedProcess_when_waitingForExit_then_returnsExitCode();
    given_completedProcess_when_waitingWithoutTimeout_then_returnsExitCode();
    given_argumentsContainingSpaces_when_buildingCommand_then_preservesArguments();
    given_golemTask_when_buildingInvocation_then_preservesArgumentsAndRejectsUnknownTask();
    given_markdownPath_when_selectingJavaFormatSources_then_ignoresIt();
    given_manyFormatterSources_when_batchingSources_then_preservesSourceCount();
    given_commandAboveLengthLimit_when_buildingCommand_then_rejectsInput();
    given_windowsOsNamePattern_when_applyingRefaster_then_rewritesOnlyOsCheck();
    given_refasterPattern_when_checkingPatch_then_detectsChangeWithoutEditingSource();
    given_gitTreeWithUntrackedFile_when_checkingStatus_then_reportsDirty();
    given_releaseTags_when_selectingNextVersion_then_requiresConsecutiveIntegers();
    given_dirtyOrDivergedTree_when_checkingRelease_then_rejectsPreflight();
    given_changelogSections_when_checkingRelease_then_requiresExactNonemptySection();
    given_podmanImageIds_when_canonicalizing_then_acceptsOnlySha256();
    given_containerBuilderImages_when_validatingJavaFeature_then_requiresDigestPinnedParity();
    given_linkedJavaProperties_when_requiringParity_then_ignoresOnlyVendorMetadata();
    given_imageRepositoryConfig_when_validating_then_acceptsCanonicalRepositories();
    given_productionRuntime_when_selectingRoots_then_includesManagementAndDiagnostics();
    given_controlledGitAndPodman_when_runningReleaseMutations_then_preservesOrderAndRedactsFailure();
    given_invalidToolOutput_when_runningTool_then_rejectsBoundAndEncoding();
    given_testGroups_when_selectingTimeouts_then_returnsConfiguredDurations();
    given_runningProcess_when_timeoutUsesForceOption_then_reportsTimeoutAndStopsProcess();
    given_runningProcess_when_timeoutExpires_then_terminatesProcess();
    given_runningProcessTree_when_timeoutExpires_then_terminatesDescendants();
    given_testNameForms_when_validatingConvention_then_acceptsOnlyCanonicalForm();
  }

  private static void
      given_testNameForms_when_validatingConvention_then_acceptsOnlyCanonicalForm() {
    if (!validTestName("given_existingWorld_when_behaviorRuns_then_stateChanges")
        || validTestName("existingWorld_when_behaviorRuns_then_stateChanges")
        || validTestName("given_ExistingWorld_when_behaviorRuns_then_stateChanges")
        || validTestName("given_existing_world_when_behaviorRuns_then_stateChanges")) {
      throw new AssertionError("test name convention mismatch");
    }
  }

  private static void
      given_repositoryAttributes_when_readingTextPolicy_then_forcesLfAndPinsCorpusBytes()
          throws Exception {
    List<String> attributes = Files.readAllLines(Path.of(".gitattributes"), StandardCharsets.UTF_8);
    if (!attributes.contains("* text=auto eol=lf")
        || !attributes.contains("tests/corpus/*.jsonl -text")) {
      throw new AssertionError("repository line-ending policy is incomplete");
    }
  }

  private static void given_oversizedFile_when_hashing_then_rejectsInput() throws Exception {
    Path path = Files.createTempFile("toktrak-build-large-", ".bin");
    try {
      try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
        channel.position(512L * 1024 * 1024);
        if (channel.write(ByteBuffer.wrap(new byte[] {0})) != 1) {
          throw new IOException("failed to write oversized-file fixture");
        }
      }
      var digest = MessageDigest.getInstance("SHA-256");
      expectFailure(
          () -> Build.updateDigestFromFileForTest(digest, path), "file exceeds 536870912 bytes");
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static void given_oversizedTree_when_listingPaths_then_rejectsInput() throws Exception {
    Path directory = Files.createTempDirectory("toktrak-build-tree-");
    try {
      for (int index = 0; index < 4; index++)
        Files.createFile(directory.resolve(Integer.toString(index)));
      expectFailure(() -> Build.treePathsForTest(directory, 3), "tree exceeds 3 entries");
    } finally {
      for (int index = 0; index < 4; index++)
        Files.deleteIfExists(directory.resolve(Integer.toString(index)));
      Files.deleteIfExists(directory);
    }
  }

  private static void given_invalidArguments_when_writingArgumentFile_then_rejectsInput() {
    expectFailure(
        () -> Build.argumentFileContentForTest(Collections.nCopies(10_001, "x")),
        "arguments exceed 10000 entries");
    expectFailure(
        () -> Build.argumentFileContentForTest(List.of("x".repeat(32 * 1024 + 1))),
        "argument exceeds 32768 UTF-8 bytes");
    expectFailure(
        () -> Build.argumentFileContentForTest(Collections.nCopies(300, "x".repeat(32 * 1024))),
        "argument file exceeds 8388608 UTF-8 bytes");
    expectFailure(
        () -> Build.argumentFileContentForTest(List.of("--reportDir\ninjected")),
        "argument contains a line break");
  }

  private static void given_templateInputs_when_validating_then_acceptsOnlyBoundedCanonicalUtf8()
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-build-templates-");
    try {
      Path template = root.resolve("home.mustache");
      Files.writeString(template, "<h1>{{title}}</h1>\n");
      if (!Build.templateSourcesForTest(root).equals(List.of(template))) {
        throw new AssertionError("valid template inventory mismatch");
      }
      Files.writeString(
          root.resolve("base.mustache"), "<main>\n{{$content}}\n{{/content}}\n</main>\n");
      Files.writeString(
          template,
          """
          {{<base.mustache}}
          {{$content}}
            <h1>{{title}}</h1>
          {{/content}}
          {{/base.mustache}}
          """);
      Build.validateTemplateLayoutsForTest(root);
      Files.writeString(template, "{{<other}}\n{{$content}}x{{/content}}\n{{/other}}\n");
      expectFailure(
          () -> Build.validateTemplateLayoutsForTest(root),
          "template requires one {{<base.mustache}}: home.mustache");
      Files.writeString(template, "{{{raw}}}\n");
      expectFailure(() -> Build.templateSourcesForTest(root), "forbidden Mustache syntax {{{");
      Files.writeString(template, "line\r\n");
      expectFailure(() -> Build.templateSourcesForTest(root), "must use LF line endings");
      Files.delete(template);
      Files.writeString(root.resolve("Home.mustache"), "safe\n");
      expectFailure(
          () -> Build.templateSourcesForTest(root),
          "template path must be lowercase relative .mustache");
    } finally {
      deleteTestTree(root);
    }
  }

  private static void given_projectSources_when_generatingEclipseProjects_then_writesValidMetadata()
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-ide-");
    try {
      for (String directory :
          List.of(
              "sources/toktrak",
              "tests/toktrak.tests",
              "tools/refaster",
              "tools/perf",
              "tests/tools",
              "deps")) {
        Files.createDirectories(root.resolve(directory));
      }
      for (String file :
          List.of(
              "sources/toktrak/module-info.java",
              "tests/toktrak.tests/module-info.java",
              "tools/Build.java",
              "tools/perf/Perf.java",
              "tools/refaster/Rules.java",
              "tests/tools/BuildTest.java",
              "deps/main.jar",
              "deps/test.jar",
              "deps/snapshot.jar",
              "deps/jmh.core.jar",
              "deps/error_prone_refaster-2.50.0.jar")) {
        Files.createFile(root.resolve(file));
      }
      Path output = root.resolve("output/ide/eclipse");
      Build.generateEclipseProjectsForTest(
          root,
          output,
          List.of(root.resolve("deps/main.jar")),
          List.of(root.resolve("deps/test.jar")),
          List.of(root.resolve("deps/snapshot.jar")),
          List.of(root.resolve("deps/jmh.core.jar")),
          root.resolve("deps/error_prone_refaster-2.50.0.jar"),
          27);

      var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
      for (String project : List.of("toktrak", "toktrak.tests", "toktrak.build", "toktrak.perf")) {
        Path directory = output.resolve(project);
        parser.parse(directory.resolve(".project").toFile());
        parser.parse(directory.resolve(".classpath").toFile());
        if (!Files.isRegularFile(directory.resolve(".settings/org.eclipse.core.resources.prefs"))
            || !Files.isRegularFile(directory.resolve(".settings/org.eclipse.jdt.core.prefs"))) {
          throw new AssertionError("missing Eclipse settings: " + project);
        }
      }
      parser.parse(output.resolve("toktrak/.factorypath").toFile());
      parser.parse(output.resolve("toktrak.perf/.factorypath").toFile());
      String generated =
          Files.readString(output.resolve("toktrak/.project"))
              + Files.readString(output.resolve("toktrak/.classpath"))
              + Files.readString(output.resolve("toktrak/.factorypath"))
              + Files.readString(output.resolve("toktrak/.settings/org.eclipse.jdt.apt.core.prefs"))
              + Files.readString(output.resolve("toktrak/.settings/org.eclipse.jdt.core.prefs"))
              + Files.readString(output.resolve("toktrak.tests/.project"))
              + Files.readString(output.resolve("toktrak.tests/.classpath"))
              + Files.readString(output.resolve("toktrak.build/.project"))
              + Files.readString(output.resolve("toktrak.build/.classpath"))
              + Files.readString(output.resolve("toktrak.perf/.project"))
              + Files.readString(output.resolve("toktrak.perf/.classpath"))
              + Files.readString(output.resolve("toktrak.perf/.factorypath"))
              + Files.readString(
                  output.resolve("toktrak.perf/.settings/org.eclipse.jdt.apt.core.prefs"));
      for (String expected :
          List.of(
              "<name>toktrak</name>",
              "<name>toktrak.tests</name>",
              "<name>toktrak.build</name>",
              "<name>toktrak.perf</name>",
              "path=\".apt_generated\"",
              "snapshot.jar",
              "toktrak.tests=ALL-UNNAMED",
              "name=\"add-reads\"",
              "name=\"module\" value=\"true\"",
              "name=\"test\" value=\"true\"",
              "name=\"add-exports\"",
              "JavaSE-27",
              "kind=\"output\" path=\"bin/default\"",
              "src/tools",
              "test/tools",
              "tools/perf",
              "jmh.core.jar",
              "path=\"/toktrak\"",
              "error_prone_refaster-2.50.0.jar",
              "excluding=\"templates/**\"",
              "org.eclipse.jdt.core.compiler.processAnnotations=enabled",
              "jstache.resourcesPath",
              "io.jstach.apt.jar")) {
        if (!generated.contains(expected)) {
          throw new AssertionError("missing Eclipse metadata: " + expected);
        }
      }
      if (generated.contains("output/modules") || generated.contains("output/runtimes")) {
        throw new AssertionError("Eclipse metadata references authoritative output");
      }
      if (Files.readString(output.resolve("toktrak.tests/.classpath"))
          .contains("excluding=\"module-info.java\"")) {
        throw new AssertionError("Eclipse test project excludes its JPMS descriptor");
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(root, 1_000);
      for (int index = paths.size() - 1; index >= 0; index--) {
        Files.deleteIfExists(paths.get(index));
      }
    }
  }

  private static void
      given_projectSources_when_generatingIntellijProjects_then_writesValidMetadata()
          throws Exception {
    Path root = Files.createTempDirectory("toktrak-intellij-");
    try {
      for (String directory :
          List.of(
              "sources/toktrak",
              "tests/toktrak.tests",
              "tools/refaster",
              "tools/perf",
              "tests/tools",
              "deps")) {
        Files.createDirectories(root.resolve(directory));
      }
      for (String file :
          List.of(
              "sources/toktrak/module-info.java",
              "tests/toktrak.tests/module-info.java",
              "tools/Build.java",
              "tools/refaster/Rules.java",
              "tests/tools/BuildTest.java",
              "deps/main.jar",
              "deps/test.jar",
              "deps/snapshot.jar",
              "deps/jmh.core.jar",
              "deps/error_prone_refaster-2.50.0.jar")) {
        Files.createFile(root.resolve(file));
      }
      try (var jar =
          new java.util.jar.JarOutputStream(
              Files.newOutputStream(root.resolve("deps/snapshot.jar")))) {
        jar.flush();
      }
      Files.writeString(
          root.resolve("sources/intellij-known-inspections.txt"),
          "IgnoreResultOfCall\nSynchronizedMethod\nUnusedDeclaration\n");
      Files.writeString(
          root.resolve("sources/intellij-inspections.txt"),
          "IgnoreResultOfCall\nSynchronizedMethod\n");
      Path idea = root.resolve(".idea");
      Files.createDirectories(idea);
      Path workspace = idea.resolve("workspace.xml");
      Files.writeString(workspace, "user-owned");
      Build.generateIntellijProjectsForTest(
          root,
          idea,
          List.of(root.resolve("deps/main.jar")),
          List.of(root.resolve("deps/test.jar")),
          List.of(root.resolve("deps/snapshot.jar")),
          List.of(root.resolve("deps/jmh.core.jar")),
          root.resolve("deps/error_prone_refaster-2.50.0.jar"),
          27);

      var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
      for (String file :
          List.of(
              "modules.xml",
              "misc.xml",
              "compiler.xml",
              "codeStyles/codeStyleConfig.xml",
              "codeStyles/Project.xml",
              "inspectionProfiles/profiles_settings.xml",
              "inspectionProfiles/Project_Default.xml",
              "modules/toktrak.iml",
              "modules/toktrak.tests.iml",
              "modules/toktrak.build.iml",
              "modules/toktrak.perf.iml")) {
        parser.parse(idea.resolve(file).toFile());
      }
      String generated =
          Files.readString(idea.resolve("modules.xml"))
              + Files.readString(idea.resolve("misc.xml"))
              + Files.readString(idea.resolve("compiler.xml"))
              + Files.readString(idea.resolve("codeStyles/codeStyleConfig.xml"))
              + Files.readString(idea.resolve("codeStyles/Project.xml"))
              + Files.readString(idea.resolve("inspectionProfiles/profiles_settings.xml"))
              + Files.readString(idea.resolve("inspectionProfiles/Project_Default.xml"))
              + Files.readString(idea.resolve("modules/toktrak.iml"))
              + Files.readString(idea.resolve("modules/toktrak.tests.iml"))
              + Files.readString(idea.resolve("modules/toktrak.build.iml"))
              + Files.readString(idea.resolve("modules/toktrak.perf.iml"));
      for (String expected :
          List.of(
              "toktrak.iml",
              "toktrak.tests.iml",
              "toktrak.build.iml",
              "toktrak.perf.iml",
              "languageLevel=\"JDK_27\"",
              "project-jdk-name=\"27\"",
              "target=\"27\"",
              "isTestSource=\"true\"",
              "packagePrefix=\"tools\"",
              "snapshot.jar",
              "jmh.core.jar",
              "type=\"module-library\"",
              "module-name=\"toktrak\"",
              "ADDITIONAL_OPTIONS_OVERRIDE",
              "--add-reads toktrak.tests=ALL-UNNAMED",
              "--add-exports toktrak/toktrak.http=toktrak.tests",
              "sourceOutputDir name=\"../../generated\"",
              "sourceTestOutputDir name=\"../../generated-test\"",
              "sourceOutputDir name=\"../../generated-perf\"",
              "sourceTestOutputDir name=\"../../generated-perf-test\"",
              "-Ajstache.resourcesPath=",
              "sourceFolder url=\"file://$MODULE_DIR$/../../tests/toktrak.tests\""
                  + " isTestSource=\"true\"",
              "output/intellij/classes",
              "TokTrak JStachio",
              "TokTrak JMH",
              "org.openjdk.jmh.generators.BenchmarkProcessor",
              "module name=\"toktrak.perf\"",
              "output/intellij/generated\" isTestSource=\"false\" generated=\"true\"",
              "output/intellij/generated-perf\" isTestSource=\"false\" generated=\"true\"",
              "jstache.resourcesPath",
              "io.jstach.apt.jar",
              "USE_PER_PROJECT_SETTINGS",
              "RIGHT_MARGIN\" value=\"100",
              "PROJECT_PROFILE\" value=\"Project Default",
              "class=\"IgnoreResultOfCall\" enabled=\"true\"",
              "class=\"SynchronizedMethod\" enabled=\"true\"",
              "class=\"UnusedDeclaration\" enabled=\"false\"",
              "content url=\"file://$MODULE_DIR$/../../tools/perf\"",
              "excludeFolder url=\"file://$MODULE_DIR$/../../sources/toktrak/templates\"",
              "excludeFolder url=\"file://$MODULE_DIR$/../../tools/perf\"")) {
        if (!generated.contains(expected)) {
          throw new AssertionError("missing IntelliJ metadata: " + expected);
        }
      }
      for (String module : List.of("toktrak", "toktrak.tests", "toktrak.build", "toktrak.perf")) {
        String metadata = Files.readString(idea.resolve("modules/" + module + ".iml"));
        if (!metadata.contains("output/intellij/classes/" + module)
            || !metadata.contains("output/intellij/test-classes/" + module)) {
          throw new AssertionError("incorrect IntelliJ output directories: " + module);
        }
      }
      String testModule = Files.readString(idea.resolve("modules/toktrak.tests.iml"));
      if (testModule.contains("packagePrefix=")
          || testModule.contains("scope=\"TEST\"")
          || !testModule.contains("tests/toktrak.tests\" isTestSource=\"true\"")) {
        throw new AssertionError("incorrect IntelliJ test source root");
      }
      String perfModule = Files.readString(idea.resolve("modules/toktrak.perf.iml"));
      if (!perfModule.contains("tools/perf\" isTestSource=\"false\"")) {
        throw new AssertionError("incorrect IntelliJ performance source root");
      }
      if (generated.contains("file://$PROJECT_DIR$/sources")
          || generated.contains("jar://$PROJECT_DIR$/output")) {
        throw new AssertionError("IntelliJ module metadata uses unresolved project dir paths");
      }
      if (generated.contains("output/modules") || generated.contains("output/runtimes")) {
        throw new AssertionError("IntelliJ metadata references authoritative output");
      }
      if (!Files.readString(workspace).equals("user-owned")) {
        throw new AssertionError("IntelliJ generation replaced workspace.xml");
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(root, 1_000);
      for (int index = paths.size() - 1; index >= 0; index--) {
        Files.deleteIfExists(paths.get(index));
      }
    }
  }

  private static void given_focusedTestArguments_when_parsing_then_requiresExplicitOnlyFlag() {
    List<String> paths = Build.focusedTestPathsForTest(List.of("--only", "tests/tools"));
    if (!paths.equals(List.of("tests/tools"))) {
      throw new AssertionError("unexpected focused test paths: " + paths);
    }
    for (List<String> arguments :
        List.of(
            List.<String>of(),
            List.of("tests/tools"),
            List.of("--only"),
            List.of("--only", "--unknown"))) {
      try {
        Build.focusedTestPathsForTest(arguments);
        throw new AssertionError("accepted invalid test arguments: " + arguments);
      } catch (IllegalArgumentException expected) {
        if (!expected.getMessage().contains("mise run test")) throw expected;
      }
    }
  }

  private static void
      given_miseTestArguments_when_decoding_then_preservesPathsAndRejectsMalformedValues() {
    String path = "tests/spaces ü % ! & ^ ' $() \\ name.java";
    String token = "p:" + Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    if (!Build.decodeMiseTestArgumentsForTest(List.of("--mise-usage", "--only", token))
        .equals(List.of("--only", path))) {
      throw new AssertionError("Mise test transport altered a path");
    }
    if (!Build.decodeMiseTestArgumentsForTest(List.of("--mise-usage")).isEmpty()) {
      throw new AssertionError("Mise test transport added paths to full test run");
    }
    for (String invalid : List.of("plain", "p:", "p:/w==", "p:not-base64?")) {
      try {
        Build.decodeMiseTestArgumentsForTest(List.of("--mise-usage", "--only", invalid));
        throw new AssertionError("accepted malformed Mise test path: " + invalid);
      } catch (IllegalArgumentException expected) {
        if (!expected.getMessage().contains("Mise test")) throw expected;
      }
    }
  }

  private static void given_javascriptTestPaths_when_selecting_then_runsOnlyRequestedFiles()
      throws Exception {
    Path expected = Path.of("tools/golem.test.mjs").toAbsolutePath();
    List<Path> selected = Build.javascriptTestPathsForTest(List.of("./tools/golem.test.mjs"));
    if (!selected.equals(List.of(expected))) {
      throw new AssertionError("unexpected JavaScript tests: " + selected);
    }
    Path tracker = Path.of("tests/tracker/usage.test.mjs").toAbsolutePath();
    if (!Build.javascriptTestPathsForTest(List.of("tests/tracker/usage.test.mjs"))
        .equals(List.of(tracker))) {
      throw new AssertionError("focused tracker test was not selected");
    }
    List<Path> trackerSuite = Build.javascriptTestPathsForTest(List.of("tests/tracker"));
    if (trackerSuite.size() != 7
        || !trackerSuite.contains(tracker)
        || !trackerSuite.contains(
            Path.of("tests/tracker/scheduler.linux.test.mjs").toAbsolutePath())
        || !trackerSuite.contains(
            Path.of("tests/tracker/scheduler.macos.test.mjs").toAbsolutePath())
        || !trackerSuite.contains(
            Path.of("tests/tracker/scheduler.win32.test.mjs").toAbsolutePath())) {
      throw new AssertionError("tracker suite selection is incomplete: " + trackerSuite);
    }
    if (!Build.javascriptTestPathsForTest(
            List.of("tests/tracker/usage.test.mjs", "./tests/tracker/usage.test.mjs"))
        .equals(List.of(tracker))) {
      throw new AssertionError("duplicate tracker test was not deduplicated");
    }
    if (!Build.javascriptTestPathsForTest(List.of("tests/tools")).isEmpty()) {
      throw new AssertionError("Java test paths were selected as JavaScript tests");
    }
    for (List<String> invalid :
        List.of(
            List.of("tests/tools", "tools/golem.test.mjs"),
            List.of("tests/tools", "tests/tracker"))) {
      try {
        Build.javascriptTestPathsForTest(invalid);
        throw new AssertionError("accepted mixed Java and JavaScript test paths: " + invalid);
      } catch (IllegalArgumentException expectedFailure) {
        if (!expectedFailure.getMessage().contains("cannot be mixed")) throw expectedFailure;
      }
    }
    expectFailure(
        () -> Build.javascriptTestPathsForTest(List.of("tests/tracker/missing.test.mjs")),
        "path does not exist");
    expectFailure(
        () -> Build.javascriptTestPathsForTest(List.of("tests/tracker/nope.mjs")),
        "path does not exist");
  }

  private static void
      given_interactiveSnapshotEnvironment_when_enforcingReadonly_then_overridesBothNames() {
    ProcessBuilder process = new ProcessBuilder("java");
    process.environment().put("selfie", "interactive");
    process.environment().put("SELFIE", "interactive");
    Build.enforceReadonlySnapshots(process);
    if (!process.environment().get("selfie").equals("readonly")
        || !process.environment().get("SELFIE").equals("readonly")) {
      throw new AssertionError("snapshot override is not read-only");
    }
  }

  private static void given_testPaths_when_selectingTests_then_returnsExpectedClasses()
      throws Exception {
    Build.TestSelection junit =
        Build.testSelectionForTest(List.of("tests/toktrak.tests/toktrak/tests/ConfigTest.java"));
    if (junit.buildTool() || !junit.classNames().equals(List.of("toktrak.tests.ConfigTest"))) {
      throw new AssertionError("unexpected JUnit selection: " + junit);
    }
    Build.TestSelection build = Build.testSelectionForTest(List.of("tests/tools"));
    if (!build.buildTool() || !build.classNames().isEmpty()) {
      throw new AssertionError("unexpected build test selection: " + build);
    }
  }

  private static void given_productionPath_when_selectingTests_then_rejectsInput() {
    expectFailure(
        () -> Build.testSelectionForTest(List.of("sources/toktrak/toktrak/Main.java")),
        "not a test source");
  }

  private static void given_symbolicSourceAncestor_when_selectingPitTargets_then_rejectsInput()
      throws Exception {
    Path target = Files.createTempDirectory("toktrak-pit-link-target-");
    Path link =
        Path.of("sources/toktrak/pit-test-link-" + ProcessHandle.current().pid()).toAbsolutePath();
    try {
      Files.writeString(target.resolve("Demo.java"), "package demo; final class Demo {}");
      Files.createSymbolicLink(link, target);
      expectFailure(
          () -> Build.pitSelectionForTest(List.of("--", link.resolve("Demo.java").toString())),
          "symbolic paths are not supported");
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(target.resolve("Demo.java"));
      Files.deleteIfExists(target);
    }
  }

  private static void given_pitArguments_when_selectingTargets_then_parsesOptions()
      throws Exception {
    Build.PitSelection all = Build.pitSelectionForTest(List.of());
    if (all.dryRun()
        || all.history()
        || all.pitHelp()
        || !all.forwardedArguments().isEmpty()
        || !all.targetClasses().contains("toktrak.http.Router")
        || all.targetClasses().stream().anyMatch(name -> name.endsWith("ViewRenderer"))) {
      throw new AssertionError("unexpected full PIT selection: " + all);
    }
    Build.PitSelection eventEnvelope =
        Build.pitSelectionForTest(
            List.of("--verbose", "true", "--", "sources/toktrak/toktrak/store/EventEnvelope.java"));
    if (!eventEnvelope.forwardedArguments().equals(List.of("--verbose", "true"))
        || !eventEnvelope
            .targetClasses()
            .equals(List.of("toktrak.store.EventEnvelope", "toktrak.store.EventEnvelope$*"))) {
      throw new AssertionError("unexpected focused PIT selection: " + eventEnvelope);
    }
    Build.PitSelection directory =
        Build.pitSelectionForTest(
            List.of(
                "--",
                Path.of("sources/toktrak/toktrak/store").toAbsolutePath().toString(),
                "sources/toktrak/toktrak/store/EventEnvelope.java"));
    if (!directory.targetClasses().equals(directory.targetClasses().stream().sorted().toList())
        || directory.targetClasses().stream().distinct().count() != directory.targetClasses().size()
        || !directory.targetClasses().contains("toktrak.store.Writer$*")) {
      throw new AssertionError(
          "PIT directory selection is not sorted and deduplicated: " + directory);
    }
    Build.PitSelection unknown = Build.pitSelectionForTest(List.of("--futurePitFlag", "value"));
    if (!unknown.forwardedArguments().equals(List.of("--futurePitFlag", "value"))) {
      throw new AssertionError("PIT arguments were not forwarded: " + unknown);
    }
    for (List<String> arguments :
        List.of(List.of("--dryRun"), List.of("--dryRun", "true"), List.of("--dryRun=true"))) {
      if (!Build.pitSelectionForTest(arguments).dryRun()) {
        throw new AssertionError("dry run not detected: " + arguments);
      }
    }
    for (List<String> arguments :
        List.of(List.of("--dryRun", "false"), List.of("--dryRun=false"))) {
      if (Build.pitSelectionForTest(arguments).dryRun()) {
        throw new AssertionError("false dry run detected: " + arguments);
      }
    }
    if (!Build.pitSelectionForTest(List.of("-h")).pitHelp()
        || !Build.pitSelectionForTest(List.of("-?")).pitHelp()) {
      throw new AssertionError("PIT help not detected");
    }
    Build.PitSelection history = Build.pitSelectionForTest(List.of("--history"));
    if (!history.history() || !history.forwardedArguments().isEmpty()) {
      throw new AssertionError("PIT history shorthand not detected: " + history);
    }
  }

  private static void given_invalidPitArguments_when_selectingTargets_then_rejectsInput() {
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--")),
        "no mutable production source files selected");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--", "--")), "duplicate PIT source separator");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--reportDir", "elsewhere")),
        "Build owns PIT option: --reportDir");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--targetClasses=other.*")),
        "Build owns PIT option: --targetClasses");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--mutationUnitSize=25")),
        "Build owns PIT option: --mutationUnitSize");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--dryRun", "true", "--dryRun=false")),
        "duplicate --dryRun");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--history", "--history")), "duplicate --history");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--historyInputLocation", "somewhere")),
        "Build owns PIT option: --historyInputLocation");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--", "sources/toktrak/module-info.java")),
        "not a mutable production source");
    expectFailure(
        () -> Build.pitSelectionForTest(List.of("--", "tests/toktrak.tests")),
        "path is outside production sources");
  }

  private static void
      given_coverageCounts_when_checkingMinimum_then_acceptsBoundaryAndRejectsBelow() {
    Build.requireCoverageForTest("instruction", 75, 25, 75);
    expectFailure(
        () -> Build.requireCoverageForTest("branch", 59, 41, 60),
        "branch coverage is below 60%: 59/100");
  }

  private static void given_pitSelection_when_buildingArguments_then_preservesRequiredOptions()
      throws Exception {
    List<String> arguments =
        Build.pitArgumentsForTest(
            Build.pitSelectionForTest(
                List.of("--fullMutationMatrix", "true", "--", "sources/toktrak/toktrak/App.java")),
            Path.of("output/mutations"));
    for (String expected :
        List.of(
            "--outputFormats",
            "HTML,XML",
            "--excludedTestClasses",
            "toktrak.tests.SnapshotTest",
            "--threads",
            "4",
            "--mutationUnitSize",
            "50",
            "--timeoutConst",
            "10000",
            "-ea,-Djunit.jupiter.execution.timeout.default=5s,-Djunit.platform.execution.listeners.deactivate=com.diffplug.selfie.*",
            "--verbosity",
            "NO_SPINNER",
            "--fullMutationMatrix")) {
      if (!arguments.contains(expected)) {
        throw new AssertionError("missing PIT argument: " + expected + " in " + arguments);
      }
    }
    if (arguments.contains("--classPath")) {
      throw new AssertionError("redundant PIT classpath argument: " + arguments);
    }
    int forwarded = arguments.indexOf("--fullMutationMatrix");
    if (forwarded < 0 || !arguments.get(forwarded + 1).equals("true")) {
      throw new AssertionError("forwarded PIT argument order changed: " + arguments);
    }
    List<String> explicitVerbosity =
        Build.pitArgumentsForTest(
            Build.pitSelectionForTest(List.of("--verbosity", "SILENT")),
            Path.of("output/mutations"));
    if (Collections.frequency(explicitVerbosity, "--verbosity") != 1
        || !explicitVerbosity.contains("SILENT")) {
      throw new AssertionError("explicit PIT verbosity was not preserved: " + explicitVerbosity);
    }
    List<String> explicitVerbose =
        Build.pitArgumentsForTest(
            Build.pitSelectionForTest(List.of("--verbose", "true")), Path.of("output/mutations"));
    if (explicitVerbose.contains("NO_SPINNER")) {
      throw new AssertionError("default verbosity overrides --verbose: " + explicitVerbose);
    }
    List<String> history =
        Build.pitArgumentsForTest(
            Build.pitSelectionForTest(List.of("--history")), Path.of("output/mutations"));
    String historyPath = Path.of("output/pit.history").toAbsolutePath().normalize().toString();
    for (String option : List.of("--historyInputLocation", "--historyOutputLocation")) {
      int index = history.indexOf(option);
      if (index < 0 || !history.get(index + 1).equals(historyPath)) {
        throw new AssertionError("missing PIT history shorthand argument: " + history);
      }
    }
  }

  private static void given_pitReports_when_validatingReport_then_acceptsOnlyValidMutations()
      throws Exception {
    Path directory = Files.createTempDirectory("toktrak-pit-report-");
    try {
      Files.writeString(directory.resolve("index.html"), "report");
      Path xml = directory.resolve("mutations.xml");
      Files.writeString(
          xml,
          "<mutations><mutation status=\"KILLED\"/><mutation status=\"SURVIVED\"/>"
              + "<mutation status=\"NO_COVERAGE\"/></mutations>");
      Build.validatePitReportForTest(directory);
      Files.writeString(xml, "<mutations><mutation status=\"NOT_STARTED\"/></mutations>");
      Build.validatePitReportForTest(directory, true);
      expectFailure(
          () -> Build.validatePitReportForTest(directory), "PIT mutation failed: NOT_STARTED");
      Files.writeString(xml, "<mutations><mutation status=\"KILLED\"/></mutations>");
      expectFailure(
          () -> Build.validatePitReportForTest(directory, true),
          "PIT dry-run mutation failed: KILLED");
      Files.writeString(xml, "<mutations/>");
      expectFailure(() -> Build.validatePitReportForTest(directory), "no mutations");
      for (String status :
          List.of(
              "TIMED_OUT", "RUN_ERROR", "MEMORY_ERROR", "NON_VIABLE", "STARTED", "NOT_STARTED")) {
        Files.writeString(xml, "<mutations><mutation status=\"" + status + "\"/></mutations>");
        expectFailure(
            () -> Build.validatePitReportForTest(directory), "PIT mutation failed: " + status);
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 100);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
    }
  }

  private static void
      given_pitArtifactSets_when_validatingDependencies_then_acceptsCompleteSetAndRejectsMissingHistoryOrInvalidJunitPlugin()
          throws Exception {
    Path directory = Files.createTempDirectory("toktrak-pit-deps-");
    try {
      Path commandLine = directory.resolve("pitest-command-line-1.jar");
      Path junitPlugin = directory.resolve("pitest-junit5-plugin-1.jar");
      Path historyPlugin = directory.resolve("pitest-history-plugin-1.jar");
      Files.write(commandLine, new byte[] {1});
      Files.write(junitPlugin, new byte[] {1});
      if (Build.pitArtifactsValidForTest(directory)) {
        throw new AssertionError("PIT artifacts accepted without history plugin");
      }
      Files.write(historyPlugin, new byte[] {1});
      if (!Build.pitArtifactsValidForTest(directory)) {
        throw new AssertionError("valid PIT artifacts rejected");
      }
      Files.write(junitPlugin, new byte[0]);
      if (Build.pitArtifactsValidForTest(directory)) {
        throw new AssertionError("empty PIT plugin accepted");
      }
      Files.delete(junitPlugin);
      Files.createDirectory(junitPlugin);
      if (Build.pitArtifactsValidForTest(directory)) {
        throw new AssertionError("directory-shaped PIT plugin accepted");
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 100);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
    }
  }

  private static void given_existingArgumentFile_when_requestingPitHelp_then_preservesFile()
      throws Exception {
    Path sentinel = Path.of("output/args/pit-help-sentinel");
    Files.createDirectories(sentinel.getParent());
    Files.writeString(sentinel, "keep");
    Process process = buildChild("pit", "--help");
    int exitCode =
        Build.waitForProcessForTest(process, Duration.ofSeconds(30), Duration.ofSeconds(2), true);
    if (exitCode != 0) throw new AssertionError("pit help failed: " + exitCode);
    if (!Files.readString(sentinel).equals("keep")) {
      throw new AssertionError("pit help changed argument files");
    }
    Files.delete(sentinel);
  }

  private static void given_completeArtifact_when_checkingInventory_then_rejectsCorruption()
      throws Exception {
    Path parent = Files.createTempDirectory("toktrak-build-artifact-");
    Path artifact = parent.resolve("cache");
    try {
      Build.rebuildArtifactForTest(artifact, "expected", BuildTest::writeArtifact, null);
      if (!Build.artifactMatchesForTest(artifact, "expected")) {
        throw new AssertionError("complete artifact missed");
      }
      for (String relative :
          List.of(
              "deps/library.jar",
              "classes/Outer$Nested.class",
              "lib/tzdb.dat",
              "report/index.html")) {
        Files.delete(artifact.resolve(relative));
        if (Build.artifactMatchesForTest(artifact, "expected")) {
          throw new AssertionError("missing artifact matched inventory: " + relative);
        }
        Build.rebuildArtifactForTest(artifact, "expected", BuildTest::writeArtifact, null);
      }
      Files.writeString(artifact.resolve("classes/Outer$Nested.class"), "changed length");
      if (Build.artifactMatchesForTest(artifact, "expected")) {
        throw new AssertionError("changed artifact length matched inventory");
      }
      Build.rebuildArtifactForTest(artifact, "expected", BuildTest::writeArtifact, null);
      Files.writeString(artifact.resolve("classes/Outer$Nested.class"), "corrupt!");
      if (Build.artifactMatchesForTest(artifact, "expected")) {
        throw new AssertionError("same-length corruption matched inventory");
      }
      Build.rebuildArtifactForTest(artifact, "expected", BuildTest::writeArtifact, null);
      Files.writeString(artifact.resolve("extra.class"), "extra");
      if (Build.artifactMatchesForTest(artifact, "expected")) {
        throw new AssertionError("unlisted artifact matched inventory");
      }
      Build.rebuildArtifactForTest(artifact, "expected", BuildTest::writeArtifact, null);
      Files.write(artifact.resolve(".toktrak-artifact"), HexFormat.of().parseHex("c3"));
      if (Build.artifactMatchesForTest(artifact, "expected")) {
        throw new AssertionError("malformed marker matched inventory");
      }
    } finally {
      deleteTestTree(parent);
    }
  }

  private static void given_malformedArtifactMarkers_when_checkingInventory_then_rejectsMarker()
      throws Exception {
    Path parent = Files.createTempDirectory("toktrak-build-marker-");
    Path artifact = parent.resolve("cache");
    try {
      Build.rebuildArtifactForTest(artifact, "expected", BuildTest::writeArtifact, null);
      Path marker = artifact.resolve(".toktrak-artifact");
      String valid = Files.readString(marker);
      String entry = valid.lines().skip(2).findFirst().orElseThrow();
      String[] fields = entry.split("\t", -1);
      for (String malformed :
          List.of(
              valid.replace("toktrak-artifact-v2", "toktrak-artifact-v1"),
              valid.replace("fingerprint\texpected", "fingerprint\tother"),
              valid.stripTrailing(),
              valid + "\n",
              valid.replace(entry, "file\tnot-a-length\t" + fields[2] + "\t" + fields[3]),
              valid.replace(entry, "file\t" + fields[1] + "\tinvalid\t" + fields[3]),
              valid.replace(entry, "file\t" + fields[1] + "\t" + fields[2] + "\t../escape"),
              valid + entry + "\n")) {
        Files.writeString(marker, malformed);
        if (Build.artifactMatchesForTest(artifact, "expected")) {
          throw new AssertionError("malformed artifact marker matched inventory: " + malformed);
        }
      }
    } finally {
      deleteTestTree(parent);
    }
  }

  private static void given_artifactFailpoints_when_rebuilding_then_nextRunPublishesCompleteTree()
      throws Exception {
    for (String failpoint :
        List.of(
            "stage",
            "validation",
            "marker",
            "invalidation",
            "deletion",
            "publish",
            "published",
            "cleanup")) {
      Path parent = Files.createTempDirectory("toktrak-build-failpoint-");
      Path artifact = parent.resolve("cache");
      try {
        Build.rebuildArtifactForTest(artifact, "old", BuildTest::writeArtifact, null);
        expectFailure(
            () ->
                Build.rebuildArtifactForTest(artifact, "new", BuildTest::writeArtifact, failpoint),
            "artifact failpoint: " + failpoint);
        Build.rebuildArtifactForTest(artifact, "new", BuildTest::writeArtifact, null);
        if (!Build.artifactMatchesForTest(artifact, "new")) {
          throw new AssertionError("failpoint recovery left incomplete artifact: " + failpoint);
        }
      } finally {
        deleteTestTree(parent);
      }
    }
  }

  private static void writeArtifact(Path directory) throws Exception {
    for (String relative :
        List.of(
            "deps/library.jar",
            "classes/Outer$Nested.class",
            "lib/tzdb.dat",
            "report/index.html")) {
      Path file = directory.resolve(relative);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "complete");
    }
  }

  private static void
      given_generatedSymbolicLinks_when_checkingInventory_then_preservesInternalTargetsAndRejectsEscapes()
          throws Exception {
    Path root = Files.createTempDirectory("toktrak-build-links-");
    Path artifact = root.resolve("runtime");
    Path external = Files.createTempFile("toktrak-build-link-target-", ".txt");
    try {
      Build.rebuildArtifactForTest(artifact, "links", BuildTest::writeLinkedArtifact, null);
      Path link = artifact.resolve("legal/java.logging/LICENSE");
      if (!Files.isSymbolicLink(link) || !Build.artifactMatchesForTest(artifact, "links")) {
        throw new AssertionError("internal generated link was not inventoried");
      }

      Files.delete(link);
      Files.createSymbolicLink(link, Path.of("../java.base/OTHER"));
      if (Build.artifactMatchesForTest(artifact, "links")) {
        throw new AssertionError("changed symbolic link target matched inventory");
      }
      Build.rebuildArtifactForTest(artifact, "links", BuildTest::writeLinkedArtifact, null);
      expectFailure(
          () ->
              Build.rebuildArtifactForTest(
                  artifact,
                  "escape",
                  staging ->
                      Files.createSymbolicLink(
                          staging.resolve("external"), external.toAbsolutePath()),
                  null),
          "generated symbolic link escapes artifact");
      if (!Build.artifactMatchesForTest(artifact, "links")) {
        throw new AssertionError("rejected symbolic link replaced complete artifact");
      }
    } finally {
      deleteTestTree(root);
      Files.deleteIfExists(external);
    }
  }

  private static void writeLinkedArtifact(Path directory) throws Exception {
    Path base = Files.createDirectories(directory.resolve("legal/java.base"));
    Path logging = Files.createDirectories(directory.resolve("legal/java.logging"));
    Files.writeString(base.resolve("LICENSE"), "license");
    Files.writeString(base.resolve("OTHER"), "license");
    Files.createSymbolicLink(logging.resolve("LICENSE"), Path.of("../java.base/LICENSE"));
  }

  private static void given_outputArtifacts_when_cleaning_then_deletesEverythingExceptHeldLock()
      throws Exception {
    Path output = Files.createTempDirectory("toktrak-clean-");
    Path lock = Files.writeString(output.resolve(".build.lock"), "");
    Files.writeString(output.resolve("unknown.txt"), "disposable");
    Files.createDirectories(output.resolve("unknown/nested"));
    Files.writeString(output.resolve("unknown/nested/artifact.bin"), "disposable");
    try {
      Build.cleanOutputForTest(output, lock);
      if (!Files.readString(lock).isEmpty()
          || !Build.treePathsForTest(output, 10).equals(List.of(output, lock))) {
        throw new AssertionError("clean did not preserve only its held lock");
      }
    } finally {
      deleteTestTree(output);
    }
  }

  private static void given_runningBuildCommand_when_acquiringBuildLock_then_rejectsCommand()
      throws Exception {
    Path lockPath = Files.createTempFile("toktrak-build-lock-", ".lock");
    try (var first = FileChannel.open(lockPath, StandardOpenOption.WRITE);
        var second = FileChannel.open(lockPath, StandardOpenOption.WRITE);
        FileLock lock = Build.acquireBuildLockForTest(first)) {
      if (!lock.isValid()) throw new AssertionError("build lock is invalid");
      expectFailure(
          () ->
              Build.acquireBuildLockForTest(
                  second, "clean", lockPath.resolveSibling("missing-data")),
          "cannot run clean while another TokTrak build command is running; wait for it to finish");
    } finally {
      Files.deleteIfExists(lockPath);
    }
  }

  private static void
      given_runningDevelopmentServer_when_requiringExclusiveBuild_then_rejectsCommand()
          throws Exception {
    Path directory = Files.createTempDirectory("toktrak-dev-lock-");
    try {
      Build.requireDevelopmentServerStoppedForTest("verify", directory);
      Path lockPath = directory.resolve("toktrak.lock");
      try (var channel =
              FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock lock = channel.lock()) {
        if (!lock.isValid()) throw new AssertionError("development lock is invalid");
        Path buildLockPath = directory.resolve("build.lock");
        try (var first =
                FileChannel.open(
                    buildLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var second = FileChannel.open(buildLockPath, StandardOpenOption.WRITE);
            FileLock buildLock = Build.acquireBuildLockForTest(first)) {
          if (!buildLock.isValid()) throw new AssertionError("build lock is invalid");
          expectFailure(
              () -> Build.acquireBuildLockForTest(second, "clean", directory),
              "cannot run clean while mise run dev is running; stop it with Ctrl+C");
        }
        for (String command :
            List.of(
                "check",
                "ci",
                "clean",
                "coverage",
                "dev",
                "ide",
                "pit",
                "prod",
                "refactor",
                "test",
                "verify")) {
          expectFailure(
              () -> Build.requireDevelopmentServerStoppedForTest(command, directory),
              "cannot run " + command + " while mise run dev is running; stop it with Ctrl+C");
        }
        Build.requireDevelopmentServerStoppedForTest("fmt", directory);
      }
      Build.requireDevelopmentServerStoppedForTest("verify", directory);
    } finally {
      deleteTestTree(directory);
    }
  }

  private static void given_validRuntimeAssets_when_buildingBundle_then_returnsCanonicalIndex()
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-assets-valid-");
    try {
      Path publicAssets = Files.createDirectories(root.resolve("public"));
      Path privateAssets = Files.createDirectories(root.resolve("private"));
      Files.writeString(publicAssets.resolve("main.css"), "body{}");
      Files.writeString(publicAssets.resolve("app.js"), "export{};");
      Files.write(
          publicAssets.resolve("logo.webp"),
          "RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1));
      Files.write(
          publicAssets.resolve("photo.avif"),
          "\0\0\0\20ftypavif".getBytes(StandardCharsets.ISO_8859_1));
      Files.write(publicAssets.resolve("font.woff2"), "wOF2".getBytes(StandardCharsets.ISO_8859_1));
      Files.writeString(
          publicAssets.resolve("logo.svg"),
          "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0\"/></svg>");
      Files.writeString(privateAssets.resolve("tracker.mjs"), "export{};");

      Build.AssetBundle bundle = Build.assetBundleForTest(root);
      Build.AssetBundle repeated = Build.assetBundleForTest(root);
      List<String> actual =
          bundle.assets().stream()
              .map(asset -> asset.scope() + "\t" + asset.logicalPath() + "\t" + asset.mediaType())
              .toList();
      List<String> expected =
          List.of(
              "private\ttracker.mjs\ttext/javascript; charset=utf-8",
              "public\tapp.js\ttext/javascript; charset=utf-8",
              "public\tfont.woff2\tfont/woff2",
              "public\tlogo.svg\timage/svg+xml",
              "public\tlogo.webp\timage/webp",
              "public\tmain.css\ttext/css; charset=utf-8",
              "public\tphoto.avif\timage/avif");
      if (!actual.equals(expected)) throw new AssertionError("unexpected assets: " + actual);
      for (Build.AssetSource asset : bundle.assets()) {
        if (!asset.sha256().matches("[0-9a-f]{64}")) {
          throw new AssertionError("noncanonical asset hash: " + asset.sha256());
        }
      }
      byte[] index = bundle.index();
      String indexText = new String(index, StandardCharsets.UTF_8);
      if (!indexText.startsWith("toktrak-assets-v1\n")
          || indexText.contains("\r")
          || index.length < 2
          || Byte.toUnsignedInt(index[0]) == 0xEF
          || index[index.length - 1] != '\n'
          || index[index.length - 2] == '\n'
          || !indexText.substring("toktrak-assets-v1\n".length()).contains("\t")) {
        throw new AssertionError("noncanonical asset index: " + indexText);
      }
      if (!Arrays.equals(index, repeated.index())
          || !bundle.fingerprint().equals(repeated.fingerprint())
          || !bundle.fingerprint().matches("[0-9a-f]{64}")) {
        throw new AssertionError("asset bundle is nondeterministic");
      }
    } finally {
      deleteTestTree(root);
    }
  }

  private static void given_svgSecurityInputs_when_buildingBundle_then_preservesValidationBoundary()
      throws Exception {
    assertAcceptedAsset(
        "logo.svg",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- metadata -->
        <svg xmlns="http://www.w3.org/2000/svg" role="img" viewBox="0 0 24 24">
          <title>Safe</title>
          <g><path d="M0 0h24v24H0z"/><circle cx="12" cy="12" r="10"/></g>
        </svg>
        """
            .getBytes(StandardCharsets.UTF_8));
    for (String svg :
        List.of(
            "<?unsafe?><svg xmlns=\"http://www.w3.org/2000/svg\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\""
                + " xmlns:xlink=\"http://www.w3.org/1999/xlink\"/>",
            "<svg xmlns=\"urn:not-svg\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" fill=\"url(#gradient)\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><image"
                + " href=\"data:image/svg+xml,x\"/></svg>")) {
      assertRejectedAsset(
          "logo.svg",
          svg.getBytes(StandardCharsets.UTF_8),
          "remove scripts, external references, and unsupported SVG features");
    }
  }

  private static void given_invalidRuntimeAssets_when_buildingBundle_then_returnsActionableErrors()
      throws Exception {
    assertRejectedAsset(
        "logo.png",
        new byte[] {0},
        "convert it to optimized WebP or AVIF, or use SVG for vector artwork");
    assertRejectedAsset("main.css", HexFormat.of().parseHex("c3"), "save the asset as valid UTF-8");
    assertRejectedAsset(
        "logo.webp",
        "invalid".getBytes(StandardCharsets.UTF_8),
        "regenerate it as a valid WebP file");
    assertRejectedAsset(
        "logo.avif",
        "invalid".getBytes(StandardCharsets.UTF_8),
        "regenerate it as a valid AVIF file");
    assertRejectedAsset(
        "font.woff2",
        "invalid".getBytes(StandardCharsets.UTF_8),
        "regenerate it as a valid WOFF2 file");
    for (String svg :
        List.of(
            "<!DOCTYPE svg><svg xmlns=\"http://www.w3.org/2000/svg\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" onclick=\"x\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" href=\"x\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" style=\"fill:red\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><foreignObject/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><style/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" fill=\"url(https://example.test/x)\"/>")) {
      assertRejectedAsset(
          "logo.svg",
          svg.getBytes(StandardCharsets.UTF_8),
          "remove scripts, external references, and unsupported SVG features");
    }
    assertRejectedAsset(
        "Bad.css",
        "body{}".getBytes(StandardCharsets.UTF_8),
        "rename runtime assets using lowercase ASCII");
  }

  private static void given_runtimeAssetBounds_when_buildingBundle_then_rejectsExcess()
      throws Exception {
    Path countRoot = Files.createTempDirectory("toktrak-assets-count-");
    try {
      Path publicAssets = Files.createDirectories(countRoot.resolve("public"));
      for (int index = 0; index < 65; index++) {
        Files.writeString(publicAssets.resolve("asset" + index + ".css"), "x");
      }
      expectFailure(
          () -> Build.assetBundleForTest(countRoot),
          "runtime assets exceed 64 files; remove or combine assets");
    } finally {
      deleteTestTree(countRoot);
    }

    Path fileRoot = Files.createTempDirectory("toktrak-assets-file-size-");
    try {
      Path oversized = Files.createDirectories(fileRoot.resolve("public")).resolve("large.woff2");
      writeSparseFile(oversized, 4L * 1024 * 1024 + 1);
      expectFailure(
          () -> Build.assetBundleForTest(fileRoot),
          "large.woff2; optimize the asset below 4194304 bytes");
    } finally {
      deleteTestTree(fileRoot);
    }

    Path totalRoot = Files.createTempDirectory("toktrak-assets-total-size-");
    try {
      Path publicAssets = Files.createDirectories(totalRoot.resolve("public"));
      for (int index = 0; index < 5; index++) {
        writeSparseFile(publicAssets.resolve("font" + index + ".woff2"), 4L * 1024 * 1024);
      }
      expectFailure(
          () -> Build.assetBundleForTest(totalRoot),
          "runtime assets exceed 16777216 total bytes; optimize or remove assets");
    } finally {
      deleteTestTree(totalRoot);
    }

    Path linkRoot = Files.createTempDirectory("toktrak-assets-link-");
    try {
      Path publicAssets = Files.createDirectories(linkRoot.resolve("public"));
      Path target = publicAssets.resolve("target.css");
      Files.writeString(target, "body{}");
      Path link = publicAssets.resolve("link.css");
      Files.createSymbolicLink(link, target.getFileName());
      expectFailure(
          () -> Build.assetBundleForTest(linkRoot),
          "replace the symbolic runtime asset with a regular file or directory");
      Files.delete(link);
      Path targetDirectory = Files.createDirectory(publicAssets.resolve("target"));
      Files.createSymbolicLink(publicAssets.resolve("link"), targetDirectory.getFileName());
      expectFailure(
          () -> Build.assetBundleForTest(linkRoot),
          "replace the symbolic runtime asset with a regular file or directory");
    } finally {
      deleteTestTree(linkRoot);
    }
  }

  private static void given_runtimeAssetBundle_when_writingModule_then_copiesAndVerifiesResources()
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-assets-copy-source-");
    Path moduleRoot = Files.createTempDirectory("toktrak-assets-copy-module-");
    try {
      byte[] css = "body{}".getBytes(StandardCharsets.UTF_8);
      Files.createDirectories(root.resolve("public"));
      Files.write(root.resolve("public/main.css"), css);
      Build.AssetBundle bundle = Build.assetBundleForTest(root);
      Build.writeAssetsForTest(bundle, moduleRoot);
      if (!Build.assetsMatchForTest(bundle, moduleRoot)
          || !Arrays.equals(css, Files.readAllBytes(moduleRoot.resolve("assets/public/main.css")))
          || !Arrays.equals(
              bundle.index(), Files.readAllBytes(moduleRoot.resolve("assets/index.tsv")))) {
        throw new AssertionError("written runtime assets do not match bundle");
      }

      Files.writeString(moduleRoot.resolve("assets/public/main.css"), "changed");
      if (Build.assetsMatchForTest(bundle, moduleRoot)) {
        throw new AssertionError("changed runtime asset matched bundle");
      }
      Build.writeAssetsForTest(bundle, moduleRoot);
      Files.delete(moduleRoot.resolve("assets/index.tsv"));
      if (Build.assetsMatchForTest(bundle, moduleRoot)) {
        throw new AssertionError("missing runtime asset index matched bundle");
      }
      Build.writeAssetsForTest(bundle, moduleRoot);
      Files.writeString(moduleRoot.resolve("assets/public/extra.css"), "extra");
      if (Build.assetsMatchForTest(bundle, moduleRoot)) {
        throw new AssertionError("unindexed runtime asset matched bundle");
      }
    } finally {
      deleteTestTree(moduleRoot);
      deleteTestTree(root);
    }
  }

  private static void given_runtimeAssets_when_fingerprintingCompilation_then_changesFingerprint()
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-assets-compile-fingerprint-");
    try {
      Path sources = Files.createDirectories(root.resolve("sources"));
      Path dependencies = Files.createDirectories(root.resolve("dependencies"));
      Files.writeString(sources.resolve("Main.java"), "final class Main {}");
      Path firstAssets = Files.createDirectories(root.resolve("first-assets/public")).getParent();
      Path secondAssets = Files.createDirectories(root.resolve("second-assets/public")).getParent();
      Files.writeString(firstAssets.resolve("public/main.css"), "body{}");
      Files.writeString(secondAssets.resolve("public/main.css"), "body { }");
      List<Path> sourcePaths = List.of(sources);
      List<Path> dependencyDirectories = List.of(dependencies);
      List<String> arguments = List.of("-Xlint:all");

      String first =
          Build.applicationCompilationFingerprintForTest(
              sourcePaths, dependencyDirectories, arguments, firstAssets);
      String second =
          Build.applicationCompilationFingerprintForTest(
              sourcePaths, dependencyDirectories, arguments, secondAssets);
      if (first.equals(second)) {
        throw new AssertionError("changed runtime asset did not change compilation fingerprint");
      }
    } finally {
      deleteTestTree(root);
    }
  }

  private static void given_templateChange_when_fingerprintingCompilation_then_changesFingerprint()
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-templates-compile-fingerprint-");
    try {
      Path sources = Files.createDirectories(root.resolve("sources"));
      Path template = sources.resolve("layout.mustache");
      Files.writeString(sources.resolve("Main.java"), "final class Main {}");
      Files.writeString(template, "<main>before</main>\n");
      Path dependencies = Files.createDirectories(root.resolve("dependencies"));
      Path assets = Files.createDirectories(root.resolve("assets/public")).getParent();
      Files.writeString(assets.resolve("public/main.css"), "body{}");
      List<Path> compileSources = List.of(sources, template);
      String before =
          Build.applicationCompilationFingerprintForTest(
              compileSources, List.of(dependencies), List.of("-Xlint:all"), assets);
      Files.writeString(template, "<main>after</main>\n");
      String after =
          Build.applicationCompilationFingerprintForTest(
              compileSources, List.of(dependencies), List.of("-Xlint:all"), assets);
      if (before.equals(after)) {
        throw new AssertionError("changed template did not change compilation fingerprint");
      }
    } finally {
      deleteTestTree(root);
    }
  }

  private static void given_explodedAssets_when_fingerprintingRuntime_then_changesFingerprint()
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-assets-runtime-fingerprint-");
    try {
      Path firstSources = Files.createDirectories(root.resolve("first-sources/public")).getParent();
      Path secondSources =
          Files.createDirectories(root.resolve("second-sources/public")).getParent();
      Files.writeString(firstSources.resolve("public/main.css"), "body{}");
      Files.writeString(secondSources.resolve("public/main.css"), "body { }");
      Build.AssetBundle firstBundle = Build.assetBundleForTest(firstSources);
      Build.AssetBundle secondBundle = Build.assetBundleForTest(secondSources);
      Path firstModule = Files.createDirectories(root.resolve("first-module"));
      Path secondModule = Files.createDirectories(root.resolve("second-module"));
      byte[] moduleInfo = new byte[] {1, 2, 3};
      Files.write(firstModule.resolve("module-info.class"), moduleInfo);
      Files.write(secondModule.resolve("module-info.class"), moduleInfo);
      Build.writeAssetsForTest(firstBundle, firstModule);
      Build.writeAssetsForTest(secondBundle, secondModule);

      String first = Build.applicationRuntimeFingerprintForTest(firstModule);
      String second = Build.applicationRuntimeFingerprintForTest(secondModule);
      if (first.equals(second)) {
        throw new AssertionError("changed runtime asset did not change linked runtime fingerprint");
      }
      if (!Build.assetsMatchForTest(firstBundle, firstModule)
          || Build.assetsMatchForTest(secondBundle, firstModule)) {
        throw new AssertionError("exploded module assets matched the wrong bundle");
      }
    } finally {
      deleteTestTree(root);
    }
  }

  private static void assertAcceptedAsset(String relative, byte[] bytes) throws Exception {
    Path root = Files.createTempDirectory("toktrak-assets-valid-");
    try {
      Path publicAssets = Files.createDirectories(root.resolve("public"));
      Files.write(publicAssets.resolve(relative), bytes);
      Build.assetBundleForTest(root);
    } finally {
      deleteTestTree(root);
    }
  }

  private static void assertRejectedAsset(String relative, byte[] bytes, String remediation)
      throws Exception {
    Path root = Files.createTempDirectory("toktrak-assets-invalid-");
    try {
      Path publicAssets = Files.createDirectories(root.resolve("public"));
      Files.write(publicAssets.resolve(relative), bytes);
      expectFailure(() -> Build.assetBundleForTest(root), relative + "; " + remediation);
    } finally {
      deleteTestTree(root);
    }
  }

  private static void writeSparseFile(Path path, long bytes) throws Exception {
    try (var channel =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(bytes - 1);
      if (channel.write(ByteBuffer.wrap(new byte[] {0})) != 1) {
        throw new IOException("failed to write sparse-file fixture");
      }
    }
  }

  private static void deleteTestTree(Path root) throws Exception {
    if (!Files.exists(root)) return;
    List<Path> paths = Build.treePathsForTest(root, 1_000);
    for (int index = paths.size() - 1; index >= 0; index--) {
      Files.deleteIfExists(paths.get(index));
    }
  }

  private static void given_completedProcess_when_waitingForExit_then_returnsExitCode()
      throws Exception {
    Process process = child("done");
    int exitCode =
        Build.waitForProcessForTest(process, Duration.ofSeconds(2), Duration.ofMillis(100));
    if (exitCode != 0) throw new AssertionError("normal child failed: " + exitCode);
  }

  private static void
      given_argumentsContainingSpaces_when_buildingCommand_then_preservesArguments() {
    List<String> command = Build.commandForTest("tool", List.of("path with spaces/Source.java"));
    if (!command.equals(List.of("tool", "path with spaces/Source.java"))) {
      throw new AssertionError("unexpected command: " + command);
    }
  }

  private static void
      given_golemTask_when_buildingInvocation_then_preservesArgumentsAndRejectsUnknownTask() {
    String script = Path.of("tools/golem.mjs").toAbsolutePath().normalize().toString();
    if (!Build.golemInvocation("golem", List.of("path with spaces"))
        .equals(List.of("node", script, "run", "path with spaces"))) {
      throw new AssertionError("golem task arguments were not preserved");
    }
    if (!Build.golemInvocation("golem-auth-check", List.of())
        .equals(List.of("node", script, "auth-check"))) {
      throw new AssertionError("golem authentication arguments were not preserved");
    }
    try {
      Build.golemInvocation("unknown", List.of());
      throw new AssertionError("expected rejection of unknown golem task");
    } catch (IllegalArgumentException ex) {
      if (!ex.getMessage().equals("unknown golem task: unknown")) {
        throw new AssertionError("unexpected rejection: " + ex.getMessage(), ex);
      }
    }
  }

  private static void given_markdownPath_when_selectingJavaFormatSources_then_ignoresIt()
      throws Exception {
    if (!Build.javaSourcePathsForTest(List.of("README.md")).isEmpty()) {
      throw new AssertionError("Markdown selected for Java formatting");
    }
  }

  private static void given_manyFormatterSources_when_batchingSources_then_preservesSourceCount() {
    var sources = new ArrayList<String>();
    for (int index = 0; index < 300; index++) sources.add("Source" + index + ".java");
    List<List<String>> batches = Build.formatterArgumentsForTest(sources);
    if (batches.size() != 3) throw new AssertionError("unexpected formatter batches: " + batches);
    int sourceCount = 0;
    for (List<String> batch : batches) {
      Build.commandForTest("dprint", batch);
      if (!batch.getFirst().equals("fmt")) throw new AssertionError("unexpected command: " + batch);
      sourceCount = Math.addExact(sourceCount, batch.size() - 1);
    }
    if (sourceCount != sources.size()) {
      throw new AssertionError("formatter sources lost: " + sourceCount);
    }
  }

  private static void given_commandAboveLengthLimit_when_buildingCommand_then_rejectsInput() {
    expectFailure(
        () -> Build.commandForTest("unused", Collections.nCopies(100, "x".repeat(300))),
        "command exceeds 24576 UTF-8 bytes");
  }

  private static void given_windowsOsNamePattern_when_applyingRefaster_then_rewritesOnlyOsCheck()
      throws Exception {
    Path directory = Files.createTempDirectory("toktrak-refaster-");
    try {
      Path source = directory.resolve("Demo.java");
      Files.writeString(
          source,
          "package probe; import java.util.Locale; final class Demo { boolean windows() { return"
              + " System.getProperty(\"os.name\").toLowerCase(Locale.ROOT).contains(\"win\"); }"
              + " boolean arbitrary(String value) { return"
              + " value.toLowerCase(Locale.ROOT).contains(\"win\"); } }");
      var arguments = new ArrayList<>(Build.refasterArgumentsForTest());
      arguments.add("-d");
      arguments.add(directory.toString());
      arguments.add(source.toString());
      Process process =
          new ProcessBuilder(Build.commandForTest(javacExecutable(), arguments))
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      int exitCode =
          Build.waitForProcessForTest(process, Duration.ofSeconds(30), Duration.ofSeconds(2), true);
      if (exitCode != 0) throw new AssertionError("Refaster failed: " + exitCode);
      String transformed = Files.readString(source);
      if (!transformed.contains("System.getProperty(\"os.name\").startsWith(\"Windows\")")) {
        throw new AssertionError("OS-name pattern was not refactored: " + transformed);
      }
      if (!transformed.contains("value.toLowerCase(Locale.ROOT).contains(\"win\")")) {
        throw new AssertionError("arbitrary string pattern was refactored: " + transformed);
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 100);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
    }
  }

  private static void
      given_refasterPattern_when_checkingPatch_then_detectsChangeWithoutEditingSource()
          throws Exception {
    Path directory =
        Files.createTempDirectory(Path.of("output").toAbsolutePath(), "refaster-check-");
    try {
      Path source = directory.resolve("Demo.java");
      String original =
          "import java.util.Locale; final class Demo { boolean windows() { return"
              + " System.getProperty(\"os.name\").toLowerCase(Locale.ROOT).contains(\"win\"); } }";
      Files.writeString(source, original);
      Path patches = directory.resolve("patches");
      Files.createDirectories(patches);
      var arguments = new ArrayList<>(Build.refasterArgumentsForTest(patches.toString()));
      arguments.add("-d");
      arguments.add(directory.toString());
      arguments.add(source.toString());
      Process process =
          new ProcessBuilder(Build.commandForTest(javacExecutable(), arguments))
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      int exitCode =
          Build.waitForProcessForTest(process, Duration.ofSeconds(30), Duration.ofSeconds(2), true);
      if (exitCode != 0) throw new AssertionError("Refaster patch check failed: " + exitCode);
      if (!Files.readString(source).equals(original)) {
        throw new AssertionError("Refaster modified the source in patch mode");
      }
      Path patch = patches.resolve("error-prone.patch");
      String content = Files.readString(patch);
      if (!content.contains("System.getProperty(\"os.name\").startsWith(\"Windows\")")) {
        throw new AssertionError("Refaster patch missed the expected change: " + content);
      }
      expectFailure(() -> Build.requireNoRefasterPatch(patch, ""), "Refaster changes required");
      Files.delete(patch);
      Build.requireNoRefasterPatch(patch, "");
      expectFailure(
          () -> Build.requireNoRefasterPatch(patch, "Failed to apply diff to file"),
          "Refaster patch generation failed");
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 100);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
    }
  }

  private static void given_gitTreeWithUntrackedFile_when_checkingStatus_then_reportsDirty()
      throws Exception {
    Path directory = Files.createTempDirectory("toktrak-git-dirty-");
    try {
      runGit(directory, "init", "--quiet");
      if (Build.isGitDirtyForTest(directory)) {
        throw new AssertionError("clean Git tree reported dirty");
      }
      Files.writeString(directory.resolve("untracked.txt"), "dirty");
      if (!Build.isGitDirtyForTest(directory)) {
        throw new AssertionError("dirty Git tree reported clean");
      }
    } finally {
      List<Path> paths = Build.treePathsForTest(directory, 1_000);
      for (int index = paths.size() - 1; index >= 0; index--)
        Files.deleteIfExists(paths.get(index));
    }
  }

  private static void
      given_releaseTags_when_selectingNextVersion_then_requiresConsecutiveIntegers() {
    if (Build.nextVersionForTest("") != 0
        || Build.nextVersionForTest("v0\nv1\n") != 2
        || !Build.releaseVersionsForTest("abc refs/tags/v0\nabc refs/tags/v0^{}\n")
            .equals(List.of(0))) {
      throw new AssertionError("release version selection mismatch");
    }
    expectFailure(() -> Build.nextVersionForTest("v1\n"), "release version gap before v1");
    expectFailure(() -> Build.nextVersionForTest("v00\n"), "invalid release tag: v00");
    expectFailure(
        () -> Build.nextVersionForTest("v2147483648\n"), "release version exceeds integer range");
  }

  private static void given_dirtyOrDivergedTree_when_checkingRelease_then_rejectsPreflight() {
    String revision = "a".repeat(40);
    Build.requireReleaseTreeForTest("trunk", "", revision, revision);
    expectFailure(
        () -> Build.requireReleaseTreeForTest("feature", "", revision, revision),
        "release requires branch trunk");
    expectFailure(
        () -> Build.requireReleaseTreeForTest("trunk", "?? file", revision, revision),
        "release requires a clean working tree");
    expectFailure(
        () -> Build.requireReleaseTreeForTest("trunk", "", revision, "b".repeat(40)),
        "trunk must be synchronized with origin/trunk");
  }

  private static void
      given_changelogSections_when_checkingRelease_then_requiresExactNonemptySection() {
    Build.requireChangelogForTest(2, "# Changelog\n\n## v2\n\n- Done.\n\n## v1\n\n- Old.\n");
    expectFailure(
        () -> Build.requireChangelogForTest(2, "# Changelog\n"),
        "CHANGELOG.md requires exactly one ## v2 section");
    expectFailure(
        () -> Build.requireChangelogForTest(2, "# Changelog\n\n## v2\n"), "## v2 section is empty");
  }

  private static void given_podmanImageIds_when_canonicalizing_then_acceptsOnlySha256() {
    String digest = "a".repeat(64);
    if (!Build.canonicalImageIdForTest(digest).equals("sha256:" + digest)
        || !Build.canonicalImageIdForTest("sha256:" + digest).equals("sha256:" + digest)) {
      throw new AssertionError("Podman image ID canonicalization mismatch");
    }
    expectFailure(
        () -> Build.canonicalImageIdForTest("sha512:" + digest),
        "Podman returned invalid image ID");
  }

  private static void
      given_containerBuilderImages_when_validatingJavaFeature_then_requiresDigestPinnedParity()
          throws Exception {
    String digest = "a".repeat(64);
    Build.requireContainerJavaFeatureForTest(
        "FROM registry.example/acme/jdk:26-jdk@sha256:" + digest + " AS build\n", 26);
    Build.requireContainerJavaFeatureForTest(
        Files.readString(Path.of("Containerfile")), Runtime.version().feature());
    expectFailure(
        () ->
            Build.requireContainerJavaFeatureForTest(
                "FROM registry.example/acme/jdk:27-jdk@sha256:" + digest + " AS build\n", 26),
        "builder uses Java 27 but the build uses Java 26");
    expectFailure(
        () ->
            Build.requireContainerJavaFeatureForTest(
                "FROM registry.example/acme/jdk:26-jdk AS build\n", 26),
        "feature-tagged, digest-pinned JDK image");
  }

  private static void
      given_linkedJavaProperties_when_requiringParity_then_ignoresOnlyVendorMetadata() {
    Runtime.Version expected = Runtime.Version.parse("26.0.2+10");
    Build.requireJavaRuntimeParityForTest(
        expected, "Property settings:\n    java.runtime.version = 26.0.2+10-LTS\n");
    for (String version : List.of("27.0.2+10", "26.0.3+10", "26.0.2+11", "26.0.2-ea+10")) {
      expectFailure(
          () ->
              Build.requireJavaRuntimeParityForTest(
                  expected, "    java.runtime.version = " + version + "\n"),
          "differs from build Java runtime");
    }
    expectFailure(
        () -> Build.requireJavaRuntimeParityForTest(expected, "java.vendor = Eclipse Adoptium\n"),
        "runtime version is missing");
    expectFailure(
        () ->
            Build.requireJavaRuntimeParityForTest(
                expected, "java.runtime.version = 26.0.2+10\njava.runtime.version = 26.0.2+10\n"),
        "runtime version is duplicated");
    expectFailure(
        () -> Build.requireJavaRuntimeParityForTest(expected, "java.runtime.version = invalid\n"),
        "runtime version is invalid");
  }

  private static void
      given_imageRepositoryConfig_when_validating_then_acceptsCanonicalRepositories() {
    for (String repository :
        List.of("localhost/toktrak", "localhost:5000/toktrak", "ghcr.io/isp-insoft-gmbh/toktrak")) {
      if (!Build.imageRepositoryForTest(repository).equals(repository)) {
        throw new AssertionError("image repository changed: " + repository);
      }
    }
    for (String repository :
        List.of(
            "", "TokTrak/image", "registry.example/image:v0", "registry.example/image@sha256")) {
      expectFailure(() -> Build.imageRepositoryForTest(repository), "TOKTRAK_IMAGE_REPOSITORY");
    }
    expectFailure(() -> Build.imageRepositoryForTest(null), "TOKTRAK_IMAGE_REPOSITORY is required");
    expectFailure(
        () -> Build.imageRepositoryForTest("localhost:99999/toktrak"),
        "TOKTRAK_IMAGE_REPOSITORY has an invalid registry port");
  }

  private static void
      given_productionRuntime_when_selectingRoots_then_includesManagementAndDiagnostics() {
    if (!Build.productionRuntimeRootsForTest()
        .equals(List.of("toktrak", "jdk.jcmd", "jdk.management.agent", "jdk.management.jfr"))) {
      throw new AssertionError("production management module roots mismatch");
    }
  }

  private static void
      given_controlledGitAndPodman_when_runningReleaseMutations_then_preservesOrderAndRedactsFailure()
          throws Exception {
    Path directory = Files.createTempDirectory("toktrak-release-tools-");
    Path log = directory.resolve("commands.log");
    try {
      Build.ToolResult git = runFakeTool(directory, log, "git-tag", "ok");
      Build.ToolResult podman = runFakeTool(directory, log, "podman-push", "ok");
      if (!git.output().equals("git-tag-output")
          || !podman.output().equals("podman-push-output")
          || !Files.readAllLines(log).equals(List.of("git-tag", "podman-push"))) {
        throw new AssertionError("controlled release command ordering mismatch");
      }
      try {
        runFakeTool(directory, log, "git-push-super-secret", "fail");
        throw new AssertionError("failed external mutation accepted");
      } catch (IllegalStateException exception) {
        if (exception.getMessage().contains("super-secret")) {
          throw new AssertionError("tool failure disclosed secret", exception);
        }
        if (!exception.getMessage().contains("controlled failure")) {
          throw new AssertionError("tool failure discarded diagnostics", exception);
        }
      }
    } finally {
      deleteTestTree(directory);
    }
  }

  private static void given_invalidToolOutput_when_runningTool_then_rejectsBoundAndEncoding()
      throws Exception {
    Path directory = Files.createTempDirectory("toktrak-tool-output-");
    Path log = directory.resolve("commands.log");
    try {
      expectFailure(
          () -> runFakeTool(directory, log, "oversized", "oversized"),
          "output exceeds 1048576 bytes");
      expectFailure(
          () -> runFakeTool(directory, log, "malformed", "malformed"), "output is not valid UTF-8");
    } finally {
      deleteTestTree(directory);
    }
  }

  private static Build.ToolResult runFakeTool(
      Path directory, Path log, String operation, String result) throws Exception {
    return Build.runToolForTest(
        javaExecutable(),
        List.of(
            "-ea",
            "-cp",
            Path.of(BuildTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString(),
            BuildTest.class.getName(),
            "fake-tool",
            log.toString(),
            operation,
            result),
        directory);
  }

  private static void runGit(Path directory, String... arguments) throws Exception {
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    int exitCode =
        Build.waitForProcessForTest(process, Duration.ofSeconds(30), Duration.ofSeconds(2), true);
    if (exitCode != 0) throw new AssertionError("Git failed: " + command);
  }

  private static void given_testGroups_when_selectingTimeouts_then_returnsConfiguredDurations() {
    if (!Build.testTimeout("--unit").equals(Duration.ofSeconds(30))) {
      throw new AssertionError("unit test timeout is not 30 seconds");
    }
    if (!Build.testTimeout("--tagged").equals(Duration.ofMinutes(10))) {
      throw new AssertionError("tagged test timeout changed");
    }
  }

  private static void given_completedProcess_when_waitingWithoutTimeout_then_returnsExitCode()
      throws Exception {
    Process process = child("done");
    int exitCode = Build.waitForProcessForTest(process);
    if (exitCode != 0) throw new AssertionError("completed process exit code changed");
  }

  private static void
      given_runningProcess_when_timeoutUsesForceOption_then_reportsTimeoutAndStopsProcess()
          throws Exception {
    Process process = child("sleep");
    expectFailure(
        () ->
            Build.waitForProcessForTest(
                process, Duration.ofMillis(20), Duration.ofMillis(100), true),
        "process timed out");
    if (process.isAlive()) throw new AssertionError("hard-timed-out child remains alive");
  }

  private static void given_runningProcess_when_timeoutExpires_then_terminatesProcess()
      throws Exception {
    Process process = child("sleep");
    expectFailure(
        () -> Build.waitForProcessForTest(process, Duration.ofMillis(20), Duration.ofSeconds(2)),
        "process timed out");
    if (process.isAlive()) throw new AssertionError("timed-out child remains alive");
  }

  private static void given_runningProcessTree_when_timeoutExpires_then_terminatesDescendants()
      throws Exception {
    Process process = child("spawn");
    ProcessHandle descendant = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (descendant == null && System.nanoTime() < deadline) {
      descendant = process.toHandle().descendants().findFirst().orElse(null);
      if (descendant == null) Thread.sleep(10);
    }
    if (descendant == null) throw new AssertionError("spawned process has no descendant");
    ProcessHandle captured = descendant;
    expectFailure(
        () -> Build.waitForProcessForTest(process, Duration.ofMillis(20), Duration.ofSeconds(2)),
        "process timed out");
    if (process.isAlive() || captured.isAlive()) {
      throw new AssertionError("timed-out process tree remains alive");
    }
  }

  private static Process child(String argument) throws Exception {
    return new ProcessBuilder(
            javaExecutable(),
            "-ea",
            "-cp",
            System.getProperty("java.class.path"),
            BuildTest.class.getName(),
            argument)
        .start();
  }

  private static Process buildChild(String... arguments) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "-ea",
                "-cp",
                System.getProperty("java.class.path"),
                Build.class.getName()));
    command.addAll(List.of(arguments));
    return new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  private static String javaExecutable() {
    return executable("java");
  }

  private static String javacExecutable() {
    return executable("javac");
  }

  private static String executable(String name) {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name)
        .toString();
  }

  private static void expectFailure(ThrowingAction action, String message) {
    try {
      action.run();
      throw new AssertionError("expected failure: " + message);
    } catch (IllegalStateException ex) {
      if (!ex.getMessage().contains(message)) {
        throw new AssertionError("unexpected failure: " + ex.getMessage(), ex);
      }
    } catch (Exception ex) {
      throw new AssertionError("unexpected exception", ex);
    }
  }

  private static boolean validTestName(String name) {
    assert name != null;
    return TEST_NAME.matcher(name).matches();
  }

  private static void requireAssertions(Class<?> owner) {
    assert owner != null;
    if (!owner.desiredAssertionStatus()) {
      throw new IllegalStateException("Java assertions must be enabled with -ea");
    }
  }

  private static void requireTestNames(Method[] methods) {
    assert methods != null;
    if (methods.length > DECLARED_METHOD_COUNT_MAX) {
      throw new IllegalStateException(
          "declared methods exceed " + DECLARED_METHOD_COUNT_MAX + " entries");
    }
    for (var method : methods) {
      int modifiers = method.getModifiers();
      if (method.isSynthetic()
          || !Modifier.isPrivate(modifiers)
          || !Modifier.isStatic(modifiers)
          || method.getReturnType() != void.class
          || method.getParameterCount() != 0) {
        continue;
      }
      if (!validTestName(method.getName())) {
        throw new IllegalStateException("invalid test name: " + method.getName());
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
