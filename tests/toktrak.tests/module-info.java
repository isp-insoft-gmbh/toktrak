module toktrak.tests {
  requires com.nimbusds.jose.jwt;
  requires io.jstach.jstachio;
  requires java.logging;
  requires java.net.http;
  requires jdk.httpserver;
  requires org.junit.jupiter.api;
  requires org.junit.jupiter.engine;
  requires org.junit.platform.engine;
  requires org.junit.platform.launcher;
  requires toktrak;

  opens selfie;
  opens toktrak.tests to
      org.junit.platform.commons,
      org.junit.jupiter.engine;
}
