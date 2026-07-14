package toktrak;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import toktrak.log.JsonLogFormatter;
import toktrak.dev.DevData;
import toktrak.health.HealthState;
import toktrak.http.Router;
import toktrak.store.DataLock;
import toktrak.store.EventLog;
import toktrak.store.Writer;
import toktrak.projection.Projection;

public final class App implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(App.class.getName());
  private final HttpServer server;
  private final ExecutorService executor;
  private final DataLock dataLock;
  private final EventLog eventLog;
  private final Writer writer;
  private final Projection projection;
  private final HealthState health;
  private final int port;
  private final AtomicBoolean closed = new AtomicBoolean();

  private App(HttpServer server, ExecutorService executor, DataLock dataLock, EventLog eventLog,
      Writer writer, Projection projection, HealthState health, int port) {
    this.server = server;
    this.executor = executor;
    this.dataLock = dataLock;
    this.eventLog = eventLog;
    this.writer = writer;
    this.projection = projection;
    this.health = health;
    this.port = port;
  }

  public static App start(String[] args, Map<String, String> env) {
    configureLogging();
    Config config = Config.from(args, env);
    if (config.corpus() != null) DevData.prepareDisposableCorpus(config.corpus(), config.dataDir());
    DataLock dataLock = DataLock.acquire(config.dataDir());
    EventLog eventLog = null;
    Writer writer = null;
    HttpServer server = null;
    ExecutorService executor = null;
    try {
      Path eventPath = config.dataDir().resolve("events.ndjson");
      EventLog.recoverTornTail(eventPath);
      eventLog = EventLog.open(eventPath);
      Projection projection = Projection.rebuild(eventLog.readAll());
      HealthState health = new HealthState();
      writer = Writer.start(eventLog, projection, health, config.clock(), config.failWrites());
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 0);
      server.createContext("/", new Router(health, config.devAuth()));
      executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.start();
      if (config.failWrites()) health.degrade("writes_failed");
      int port = server.getAddress().getPort();
      LOG.info("bound HTTP server on port " + port);
      return new App(server, executor, dataLock, eventLog, writer, projection, health, port);
    } catch (Exception ex) {
      if (server != null) server.stop(0);
      if (executor != null) executor.close();
      if (writer != null) writer.close();
      if (eventLog != null) eventLog.close();
      dataLock.close();
      if (ex instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("cannot start TokTrak", ex);
    }
  }

  private static void configureLogging() {
    Logger root = Logger.getLogger("");
    for (Handler handler : root.getHandlers()) root.removeHandler(handler);
    var console = new ConsoleHandler();
    boolean quiet = Boolean.getBoolean("toktrak.quiet");
    console.setLevel(quiet ? Level.OFF : Level.ALL);
    console.setFormatter(new JsonLogFormatter());
    root.addHandler(console);
    root.setLevel(quiet ? Level.OFF : Level.INFO);
  }

  public int port() { return port; }
  public Writer writer() { return writer; }
  public Projection projection() { return projection; }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      server.stop(5);
    } finally {
      shutdownExecutor();
      try {
        writer.close();
      } finally {
        try {
          eventLog.close();
        } finally {
          dataLock.close();
        }
      }
    }
  }

  private void shutdownExecutor() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException ex) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
