module toktrak {
  requires com.fasterxml.jackson.annotation;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.databind;
  requires com.nimbusds.jose.jwt;
  requires java.logging;
  requires jdk.httpserver;

  exports toktrak;
  exports toktrak.health;
  exports toktrak.store;
  exports toktrak.projection;
}
