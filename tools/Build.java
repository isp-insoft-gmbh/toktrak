import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class Build {
  private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
  private static final Path OUTPUT = ROOT.resolve("output");
  private static final Path CLASSES = OUTPUT.resolve("classes");
  private static final Path MAIN_DEPS = OUTPUT.resolve("deps/main");
  private static final Path TEST_DEPS = OUTPUT.resolve("deps/test");
  private static final Path TEST_CLASSES = OUTPUT.resolve("test-classes");

  private Build() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) fail("command required: deps, compile, test, jlink-prod, or dev");
    switch (args[0]) {
      case "deps" -> deps();
      case "compile" -> compile();
      case "test" -> test();
      case "jlink-prod" -> jlinkProd();
      case "dev" -> dev(List.of(args).subList(1, args.length));
      default -> fail("unknown command: " + args[0]);
    }
  }

  private static void deps() throws Exception {
    resolve("sources/main-deps.txt", MAIN_DEPS);
    resolve("sources/test-deps.txt", TEST_DEPS);
  }

  private static void resolve(String dependencyFile, Path output) throws Exception {
    Files.createDirectories(output);
    run(List.of(
        javaExecutable(),
        "-jar",
        ROOT.resolve("vendored/jresolve.jar").toString(),
        "--use-module-names",
        "--output-directory=" + output,
        "--dependency-file=" + ROOT.resolve(dependencyFile)));
  }

  private static void compile() throws Exception {
    Files.createDirectories(CLASSES);
    List<String> command = new ArrayList<>();
    command.add(javacExecutable());
    command.add("-Xlint:all");
    command.add("-d");
    command.add(CLASSES.toString());
    command.add("--module-path");
    command.add(joinJars(MAIN_DEPS));
    command.addAll(javaFiles(ROOT.resolve("sources/toktrak")));
    run(command);
  }

  private static void test() throws Exception {
    compile();
    Files.createDirectories(TEST_CLASSES);
    Path junit = onlyJar(TEST_DEPS);
    String classPath = String.join(
        java.io.File.pathSeparator,
        TEST_CLASSES.toString(),
        CLASSES.toString(),
        junit.toString());

    List<String> compileTests = new ArrayList<>();
    compileTests.add(javacExecutable());
    compileTests.add("-Xlint:all");
    compileTests.add("-cp");
    compileTests.add(classPath);
    compileTests.add("-d");
    compileTests.add(TEST_CLASSES.toString());
    compileTests.addAll(javaFiles(ROOT.resolve("tests")));
    run(compileTests);

    run(List.of(
        javaExecutable(),
        "-jar",
        junit.toString(),
        "execute",
        "--disable-ansi-colors",
        "--class-path",
        String.join(java.io.File.pathSeparator, TEST_CLASSES.toString(), CLASSES.toString(), joinJars(MAIN_DEPS)),
        "--scan-class-path"));
  }

  private static void jlinkProd() throws Exception {
    compile();
    verifyModules(MAIN_DEPS);
    Path image = OUTPUT.resolve("runtimes/prod");
    deleteTree(image);
    Files.createDirectories(image.getParent());
    String modulePath = String.join(
        java.io.File.pathSeparator,
        CLASSES.toString(),
        joinJars(MAIN_DEPS),
        Path.of(System.getProperty("java.home"), "jmods").toString());
    run(List.of(
        jlinkExecutable(),
        "--module-path", modulePath,
        "--add-modules", "toktrak",
        "--output", image.toString(),
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages"));
    Path java = image.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    run(List.of(java.toString(), "-m", "toktrak/toktrak.Main", "--help"));
  }

  private static void dev(List<String> args) throws Exception {
    compile();
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("--module-path");
    command.add(String.join(java.io.File.pathSeparator, CLASSES.toString(), joinJars(MAIN_DEPS)));
    command.add("-m");
    command.add("toktrak/toktrak.Main");
    command.addAll(args.stream().filter(arg -> !arg.equals("--")).toList());
    run(command);
  }

  private static void verifyModules(Path directory) throws Exception {
    if (!Files.isDirectory(directory)) fail("dependency directory missing: " + directory);
    try (Stream<Path> paths = Files.list(directory)) {
      for (Path jar : paths.filter(Build::isJar).sorted().toList()) {
        List<String> command = List.of("jar", "--describe-module", "--file", jar.toString(), "--release", "9");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0 || output.toLowerCase(Locale.ROOT).contains("automatic")) {
          fail("automatic or unreadable production module: " + jar + "\n" + output);
        }
        String first = output.lines().filter(line -> !line.isBlank()).findFirst().orElse("").trim();
        if (first.isEmpty() || first.startsWith("No module descriptor")) {
          fail("production dependency has no module name: " + jar + "\n" + output);
        }
      }
    }
  }

  private static List<String> javaFiles(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths.filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .map(Path::toString)
          .toList();
    }
  }

  private static Path onlyJar(Path directory) throws IOException {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(Build::isJar).findFirst()
          .orElseThrow(() -> new IllegalStateException("no jar in " + directory));
    }
  }

  private static String joinJars(Path directory) throws IOException {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(Build::isJar)
          .sorted()
          .map(Path::toString)
          .reduce((a, b) -> a + java.io.File.pathSeparator + b)
          .orElse("");
    }
  }

  private static boolean isJar(Path path) {
    return path.getFileName().toString().endsWith(".jar");
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (Stream<Path> paths = Files.walk(path)) {
      for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(child);
    }
  }

  private static void run(List<String> command) throws Exception {
    System.out.println("+ " + String.join(" ", command));
    int code = new ProcessBuilder(command).inheritIO().start().waitFor();
    if (code != 0) fail("command failed with exit code " + code);
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
  }

  private static String javacExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac").toString();
  }

  private static String jlinkExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "jlink.exe" : "jlink").toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
  }

  private static void fail(String message) {
    throw new IllegalStateException(message);
  }
}
