module toktrak.tests {
  requires java.logging;
  requires java.net.http;
  requires org.junit.jupiter.api;
  requires org.junit.platform.engine;
  requires org.junit.platform.launcher;
  requires toktrak;

  opens toktrak.tests to org.junit.platform.commons, org.junit.jupiter.engine;
}
