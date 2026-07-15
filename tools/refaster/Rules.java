package tools.refaster;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import java.util.Locale;

public final class Rules {
  private Rules() {}

  static final class WindowsOsName {
    @BeforeTemplate
    boolean before() {
      return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    @AfterTemplate
    boolean after() {
      return System.getProperty("os.name").startsWith("Windows");
    }
  }
}
