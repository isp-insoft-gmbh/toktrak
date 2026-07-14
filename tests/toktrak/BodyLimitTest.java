package toktrak;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import toktrak.http.HttpSupport;

final class BodyLimitTest {
  @Test
  void rejectsBodyAboveLimit() throws Exception {
    var body = new ByteArrayInputStream(new byte[1025]);
    var ex = assertThrows(IllegalArgumentException.class, () -> HttpSupport.readLimited(body, 1024));
    assertEquals("request body exceeds 1024 bytes", ex.getMessage());
  }
}
