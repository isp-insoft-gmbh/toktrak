package toktrak;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import toktrak.auth.AuthService;
import toktrak.auth.OidcClient;
import toktrak.dev.DevData;
import toktrak.fx.FxService;
import toktrak.health.HealthState;
import toktrak.http.Assets;
import toktrak.http.Router;
import toktrak.identity.IdentityService;
import toktrak.log.JsonLogFormatter;
import toktrak.projection.Projection;
import toktrak.store.DataLock;
import toktrak.store.EventLog;
import toktrak.store.Writer;
import toktrak.usage.UsageService;

public final class App implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(App.class.getName());
  private static final int HTTP_BACKLOG = 128;
  private static final int HTTP_WORKER_COUNT = 64;
  private static final int HTTP_QUEUE_CAPACITY = 256;
  private static final byte[] DEVELOPMENT_SESSION_SECRET =
      "toktrak-development-cookie-signing".getBytes(StandardCharsets.UTF_8);
  private HttpServer server;
  private ExecutorService executor;
  private final DataLock dataLock;
  private EventLog eventLog;
  private Writer writer;
  private Projection projection;
  private FxService fxService;
  private int port;
  private final AtomicBoolean closed = new AtomicBoolean();

  private App(Config config, Assets assets, DataLock dataLock) throws IOException {
    assert config != null;
    assert assets != null;
    assert dataLock != null;
    this.dataLock = dataLock;
    boolean initialized = false;
    try {
      initialize(config, assets);
      initialized = true;
    } finally {
      if (!initialized) close();
    }
  }

  public static App start(String[] args, Map<String, String> environment) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(environment, "environment");
    configureLogging();
    Config config = Config.from(args, environment);
    Assets assets = Assets.load();
    try {
      return new App(config, assets, DataLock.acquire(config.dataDirectory()));
    } catch (IOException exception) {
      throw new IllegalStateException("cannot start TokTrak", exception);
    }
  }

  private void initialize(Config config, Assets assets) throws IOException {
    assert config != null;
    assert assets != null;
    if (config.corpus() != null)
      DevData.prepareDisposableCorpus(config.corpus(), config.dataDirectory());
    Path eventPath = config.dataDirectory().resolve("events.ndjson");
    EventLog.recoverTornTail(eventPath);
    eventLog = EventLog.open(eventPath);
    projection = Projection.empty();
    eventLog.replay(projection::apply);
    HealthState health = new HealthState();
    writer = Writer.start(eventLog, projection, health, config.clock(), config.failWrites());
    executor =
        new ThreadPoolExecutor(
            HTTP_WORKER_COUNT,
            HTTP_WORKER_COUNT,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY),
            Thread.ofVirtual().name("toktrak-http-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    server =
        HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), HTTP_BACKLOG);
    URI baseUri = baseUri(config, server.getAddress().getPort());
    AuthService auth = auth(config, projection, writer, baseUri);
    var usage = new UsageService(writer, config.clock());
    server.createContext(
        "/",
        new Router(health, config.devAuth(), executor, assets, auth, usage, projection, baseUri));
    server.setExecutor(Runnable::run);
    server.start();
    if (!config.devAuth()) fxService = FxService.start(writer);
    if (config.failWrites()) health.degrade("writes_failed");
    port = server.getAddress().getPort();
    if (port < 0 || port > 65_535)
      throw new IllegalStateException("HTTP server returned invalid port");
    String readyUrl = baseUri.toString();
    LOG.info("TokTrak ready at " + readyUrl + (readyUrl.endsWith("/") ? "" : "/"));
  }

  private static URI baseUri(Config config, int boundPort) {
    assert config != null;
    assert boundPort >= 0 && boundPort <= 65_535;
    return URI.create(
        config.devAuth()
            ? "http://127.0.0.1:" + boundPort
            : Objects.requireNonNull(config.baseUrl()));
  }

  private static AuthService auth(
      Config config, Projection projection, Writer writer, URI baseUri) {
    assert config != null;
    assert projection != null;
    assert writer != null;
    assert baseUri != null;
    if (config.devAuth()) {
      byte[] tokenPepper = new byte[32];
      var identities = new IdentityService(writer, projection, tokenPepper);
      return AuthService.development(
          baseUri, config.clock(), projection, identities, DEVELOPMENT_SESSION_SECRET);
    }
    var identities = new IdentityService(writer, projection, config.tokenPepperBytes());
    var oidc =
        new OidcClient(
            URI.create(config.oidcDiscoveryUrl()),
            config.oidcClientId(),
            config.oidcClientSecret(),
            config.allowedDomain(),
            config.clock());
    return AuthService.production(
        baseUri, config.clock(), projection, identities, config.sessionSecretBytes(), oidc);
  }

  private static void configureLogging() {
    Logger root = Logger.getLogger("");
    Handler[] handlers = root.getHandlers();
    if (handlers.length > 16) throw new IllegalStateException("logging handlers exceed 16 entries");
    for (Handler handler : handlers) root.removeHandler(handler);
    var console = new ConsoleHandler();
    boolean quiet = Boolean.getBoolean("toktrak.quiet");
    console.setLevel(quiet ? Level.OFF : Level.ALL);
    console.setFormatter(new JsonLogFormatter());
    root.addHandler(console);
    root.setLevel(quiet ? Level.OFF : Level.INFO);
  }

  public int port() {
    assert port >= 0 && port <= 65_535;
    return port;
  }

  public InetAddress bindAddress() {
    InetAddress address = server.getAddress().getAddress();
    assert address != null;
    return address;
  }

  public Writer writer() {
    assert writer != null;
    return writer;
  }

  public Projection projection() {
    assert projection != null;
    return projection;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      if (server != null) server.stop(5);
    } finally {
      closeExecutorAndStorage();
    }
  }

  private void closeExecutorAndStorage() {
    try {
      if (executor != null) shutdownExecutor();
    } finally {
      if (fxService != null) fxService.close();
      closeWriterAndStorage();
    }
  }

  private void closeWriterAndStorage() {
    try {
      if (writer != null) writer.close();
    } finally {
      closeStorage();
    }
  }

  private void closeStorage() {
    try {
      if (eventLog != null) eventLog.close();
    } finally {
      dataLock.close();
    }
  }

  private void shutdownExecutor() {
    shutdownExecutor(executor);
  }

  private static void shutdownExecutor(ExecutorService executor) {
    assert executor != null;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          LOG.warning("HTTP executor did not terminate");
        }
      }
    } catch (InterruptedException exception) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
