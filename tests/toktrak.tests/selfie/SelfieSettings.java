package selfie;

import com.diffplug.selfie.junit5.SelfieSettingsAPI;
import java.io.File;

public final class SelfieSettings extends SelfieSettingsAPI {
  @Override
  public File getRootFolder() {
    return new File("tests/toktrak.tests");
  }

  @Override
  public boolean getAllowMultipleEquivalentWritesToOneLocation() {
    return false;
  }
}
