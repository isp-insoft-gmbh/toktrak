package toktrak.usage;

import java.time.Instant;
import java.util.Objects;
import toktrak.ClockSource;
import toktrak.projection.Projection.UserKey;
import toktrak.store.WriteCommand;
import toktrak.store.Writer;

public final class UsageService {
  private final Writer writer;
  private final ClockSource clock;

  public UsageService(Writer writer, ClockSource clock) {
    this.writer = Objects.requireNonNull(writer, "writer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public UsageUpload upload(UserKey owner, byte[] body) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(body, "body");
    Instant receivedAt = Objects.requireNonNull(clock.instant(), "clock instant");
    UsageUpload upload = UsageUpload.parse(body, receivedAt);
    writer.write(WriteCommand.usageUploaded(owner, upload.data()));
    return upload;
  }
}
