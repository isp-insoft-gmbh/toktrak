package toktrak.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;

public final class Json {
  private static final int NESTING_DEPTH_MAX = 32;
  private static final int DOCUMENT_BYTES_MAX = 10 * 1024 * 1024 - 1;
  private static final long DOCUMENT_CHARACTERS_MAX = DOCUMENT_BYTES_MAX;
  private static final long TOKENS_MAX = 100_000;
  private static final int NUMBER_CHARACTERS_MAX = 256;
  private static final int STRING_CHARACTERS_MAX = 1024 * 1024;
  private static final ObjectMapper MAPPER = mapper();

  private Json() {}

  public static ObjectMapper mapper() {
    var constraints =
        StreamReadConstraints.builder()
            .maxNestingDepth(NESTING_DEPTH_MAX)
            .maxDocumentLength(DOCUMENT_CHARACTERS_MAX)
            .maxTokenCount(TOKENS_MAX)
            .maxNumberLength(NUMBER_CHARACTERS_MAX)
            .maxStringLength(STRING_CHARACTERS_MAX)
            .build();
    assert constraints != null;
    JsonFactory factory = JsonFactory.builder().streamReadConstraints(constraints).build();
    assert factory != null;
    var module = new SimpleModule();
    module.addSerializer(
        Instant.class,
        new JsonSerializer<>() {
          @Override
          public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider)
              throws IOException {
            assert value != null;
            assert generator != null;
            generator.writeString(value.toString());
          }
        });
    module.addDeserializer(
        Instant.class,
        new JsonDeserializer<>() {
          @Override
          public Instant deserialize(JsonParser parser, DeserializationContext context)
              throws IOException {
            assert parser != null;
            return Instant.parse(parser.getText());
          }
        });
    ObjectMapper mapper =
        new ObjectMapper(factory)
            .registerModule(module)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    assert mapper != null;
    return mapper;
  }

  public static String write(Object value) {
    assert value != null;
    try {
      String json = MAPPER.writeValueAsString(value);
      assert json != null;
      return json;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot serialize JSON", exception);
    }
  }

  public static <T> T read(String value, Class<T> type) {
    assert value != null;
    assert type != null;
    try {
      T result = MAPPER.readValue(value, type);
      assert result != null;
      return result;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("cannot parse JSON", exception);
    }
  }

  public static <T> T read(byte[] value, Class<T> type) {
    assert value != null;
    assert type != null;
    if (value.length > DOCUMENT_BYTES_MAX) {
      throw new IllegalArgumentException("JSON document exceeds " + DOCUMENT_BYTES_MAX + " bytes");
    }
    try {
      T result = MAPPER.readValue(value, type);
      assert result != null;
      return result;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("cannot parse JSON", exception);
    }
  }
}
