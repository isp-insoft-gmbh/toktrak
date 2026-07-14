package toktrak.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;

public final class Json {
  private static final ObjectMapper MAPPER = mapper();

  private Json() {}

  public static ObjectMapper mapper() {
    var module = new SimpleModule();
    module.addSerializer(Instant.class, new JsonSerializer<>() {
      @Override public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeString(value.toString());
      }
    });
    module.addDeserializer(Instant.class, new JsonDeserializer<>() {
      @Override public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return Instant.parse(parser.getText());
      }
    });
    return new ObjectMapper().registerModule(module);
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException ex) {
      throw new IllegalStateException("cannot serialize JSON", ex);
    }
  }

  public static <T> T read(String value, Class<T> type) {
    try {
      return MAPPER.readValue(value, type);
    } catch (IOException | RuntimeException ex) {
      throw new IllegalStateException("cannot parse JSON", ex);
    }
  }
}
