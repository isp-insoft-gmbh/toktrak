package toktrak.http;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

public record RequestContext(
    String requestId, String method, String path, String userId, String tokenId, String mode) {
  private static final int REQUEST_ID_CHARACTERS_MAX = 64;
  private static final int METHOD_CHARACTERS_MAX = 32;
  private static final int PATH_CHARACTERS_MAX = 2 * 1024;
  private static final int ID_CHARACTERS_MAX = 128;
  private static final int MODE_CHARACTERS_MAX = 16;
  private static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();

  public RequestContext {
    requireText(requestId, REQUEST_ID_CHARACTERS_MAX, "requestId");
    requireText(method, METHOD_CHARACTERS_MAX, "method");
    requireText(path, PATH_CHARACTERS_MAX, "path");
    if (userId != null) requireText(userId, ID_CHARACTERS_MAX, "userId");
    if (tokenId != null) requireText(tokenId, ID_CHARACTERS_MAX, "tokenId");
    requireText(mode, MODE_CHARACTERS_MAX, "mode");
  }

  public static RequestContext create(String method, String path, String mode) {
    var context = new RequestContext(UUID.randomUUID().toString(), method, path, null, null, mode);
    assert context.requestId.length() <= REQUEST_ID_CHARACTERS_MAX;
    return context;
  }

  public static RequestContext currentOrNull() {
    RequestContext context = CURRENT.isBound() ? CURRENT.get() : null;
    assert !CURRENT.isBound() || context != null;
    return context;
  }

  public static <T> T with(RequestContext context, ThrowingSupplier<T> action) throws IOException {
    assert context != null;
    assert action != null;
    assert !CURRENT.isBound();
    var result = new Result<T>();
    try {
      ScopedValue.where(CURRENT, context)
          .run(
              () -> {
                try {
                  result.value = action.get();
                } catch (IOException exception) {
                  throw new RequestIOException(exception);
                }
              });
    } catch (RequestIOException exception) {
      throw new IOException(exception.getCause().getMessage(), exception);
    }
    return result.value;
  }

  @FunctionalInterface
  public interface ThrowingSupplier<T> {
    T get() throws IOException;
  }

  private static final class Result<T> {
    private T value;
  }

  private static final class RequestIOException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private RequestIOException(IOException cause) {
      super(cause);
    }

    @Override
    public IOException getCause() {
      return (IOException) super.getCause();
    }
  }

  private static void requireText(String value, int charactersMax, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
    if (value.length() > charactersMax) {
      throw new IllegalArgumentException(name + " exceeds " + charactersMax + " characters");
    }
  }
}
