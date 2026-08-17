package toktrak.http;

import java.io.IOException;

final class RenderFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  RenderFailure(String template, String model, String renderer, String kind, IOException cause) {
    super(message(template, model, renderer, kind), cause, false, false);
  }

  private static String message(String template, String model, String renderer, String kind) {
    if (!template.matches("[a-z-]+\\.mustache")
        || !model.matches("[A-Z][A-Za-z0-9]+")
        || !renderer.equals(model + "Renderer")
        || (!kind.equals("renderer_failure") && !kind.equals("output_limit"))) {
      throw new IllegalArgumentException("render diagnostic is invalid");
    }
    return "template=" + template + " model=" + model + " renderer=" + renderer + " kind=" + kind;
  }
}
