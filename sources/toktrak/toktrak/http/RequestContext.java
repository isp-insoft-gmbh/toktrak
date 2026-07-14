package toktrak.http;

import java.util.UUID;
import java.util.concurrent.Callable;

public record RequestContext(
    String requestId,
    String method,
    String path,
    String userId,
    String tokenId,
    String mode) {
  private static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();

  public static RequestContext create(String method, String path, String mode) {
    return new RequestContext(UUID.randomUUID().toString(), method, path, null, null, mode);
  }

  public static RequestContext currentOrNull() {
    return CURRENT.isBound() ? CURRENT.get() : null;
  }

  public static <T> T with(RequestContext context, Callable<T> action) throws Exception {
    return ScopedValue.where(CURRENT, context).call(action::call);
  }
}
