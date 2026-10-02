import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Bounded JSON values for agent results, forge replies, and workflow output.
sealed interface Json {
  int BYTES_MAX = 1024 * 1024;
  int DEPTH_MAX = 32;
  int ENTRIES_MAX = 10_000;

  record ObjectValue(Map<String, Json> members) implements Json {
    public ObjectValue {
      members = Map.copyOf(members);
    }

    Json get(String key) {
      return members.getOrDefault(key, NullValue.INSTANCE);
    }
  }

  record ArrayValue(List<Json> elements) implements Json {
    public ArrayValue {
      elements = List.copyOf(elements);
    }
  }

  record StringValue(String value) implements Json {}

  record NumberValue(BigDecimal value) implements Json {}

  record BooleanValue(boolean value) implements Json {}

  enum NullValue implements Json {
    INSTANCE
  }

  static Json parse(String source) {
    return new Parser(source).parse();
  }

  static String encode(Json value) {
    var out = new StringBuilder();
    var pending = new ArrayDeque<Object>();
    pending.push(value);
    while (!pending.isEmpty()) {
      var item = pending.pop();
      if (item instanceof String text) {
        out.append(text);
      } else {
        switch ((Json) item) {
          case StringValue text -> quote(out, text.value());
          case NumberValue number -> out.append(number.value().toString());
          case BooleanValue flag -> out.append(flag.value());
          case NullValue ignored -> out.append("null");
          case ArrayValue array -> {
            pending.push("]");
            for (int index = array.elements().size() - 1; index >= 0; index--) {
              pending.push(array.elements().get(index));
              if (index != 0) pending.push(",");
            }
            pending.push("[");
          }
          case ObjectValue object -> {
            pending.push("}");
            var entries = new ArrayList<>(object.members().entrySet());
            for (int index = entries.size() - 1; index >= 0; index--) {
              var entry = entries.get(index);
              pending.push(entry.getValue());
              pending.push(":");
              pending.push(new StringValue(entry.getKey()));
              if (index != 0) pending.push(",");
            }
            pending.push("{");
          }
        }
      }
      if (out.length() > BYTES_MAX) throw new IllegalArgumentException("JSON output exceeds limit");
    }
    return out.toString();
  }

  private static void quote(StringBuilder out, String text) {
    out.append('"');
    for (int index = 0; index < text.length(); index++) {
      char c = text.charAt(index);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else if (Character.isHighSurrogate(c)) {
            if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1)))
              throw new IllegalArgumentException("unpaired JSON surrogate");
            out.append(c).append(text.charAt(++index));
          } else if (Character.isLowSurrogate(c))
            throw new IllegalArgumentException("unpaired JSON surrogate");
          else out.append(c);
        }
      }
    }
    out.append('"');
  }

  final class Parser {
    private final String input;
    private int index;
    private int entries;

    private Parser(String input) {
      if (input == null || input.length() > BYTES_MAX)
        throw new IllegalArgumentException("JSON input exceeds limit");
      this.input = input;
    }

    private Json parse() {
      var frames = new ArrayDeque<Frame>();
      Json result = null;
      while (true) {
        whitespace();
        if (frames.isEmpty() && result != null) {
          if (index != input.length()) throw error("trailing data");
          return result;
        }
        var frame = frames.peek();
        if (frame != null && frame.next == Next.KEY) {
          if (take('}')) {
            frames.pop();
            result = accept(frames, new ObjectValue(frame.object), result);
          } else {
            if (!at('"')) throw error("expected object key");
            frame.key = string();
            frame.next = Next.COLON;
          }
          continue;
        }
        if (frame != null && frame.next == Next.COLON) {
          if (!take(':')) throw error("expected colon");
          frame.next = Next.VALUE;
          continue;
        }
        if (frame != null && frame.next == Next.AFTER) {
          if (take(frame.object == null ? ']' : '}')) {
            frames.pop();
            result =
                accept(
                    frames,
                    frame.object == null
                        ? new ArrayValue(frame.array)
                        : new ObjectValue(frame.object),
                    result);
          } else if (take(',')) {
            frame.next = frame.object == null ? Next.VALUE : Next.KEY_REQUIRED;
          } else throw error("expected comma or end of container");
          continue;
        }
        if (frame != null && frame.next == Next.KEY_REQUIRED) {
          if (!at('"')) throw error("expected object key after comma");
          frame.key = string();
          frame.next = Next.COLON;
          continue;
        }
        if (frame != null && frame.next == Next.FIRST && take(']')) {
          frames.pop();
          result = accept(frames, new ArrayValue(frame.array), result);
          continue;
        }
        if (frame != null && frame.next == Next.VALUE && (at('}') || at(']')))
          throw error("expected value after comma or colon");
        if (take('{')) {
          if (frames.size() >= DEPTH_MAX) throw error("JSON nesting exceeds limit");
          frames.push(new Frame(true));
        } else if (take('[')) {
          if (frames.size() >= DEPTH_MAX) throw error("JSON nesting exceeds limit");
          frames.push(new Frame(false));
        } else {
          Json value;
          if (at('"')) value = new StringValue(string());
          else if (at('-') || digit()) value = number();
          else if (literal("true")) value = new BooleanValue(true);
          else if (literal("false")) value = new BooleanValue(false);
          else if (literal("null")) value = NullValue.INSTANCE;
          else throw error("expected JSON value");
          result = accept(frames, value, result);
        }
      }
    }

    private Json accept(ArrayDeque<Frame> frames, Json value, Json previous) {
      var frame = frames.peek();
      if (frame == null) {
        if (previous != null) throw error("multiple root values");
        return value;
      }
      if (++entries > ENTRIES_MAX) throw error("too many JSON values");
      if (frame.object != null) {
        if (frame.object.putIfAbsent(frame.key, value) != null) throw error("duplicate object key");
        frame.key = null;
      } else frame.array.add(value);
      frame.next = Next.AFTER;
      return previous;
    }

    private Json number() {
      int start = index;
      take('-');
      if (!take('0')) {
        if (!between('1', '9')) throw error("invalid number");
        while (digit()) index++;
      }
      if (take('.')) {
        if (!digit()) throw error("invalid fraction");
        while (digit()) index++;
      }
      if (take('e') || take('E')) {
        if (!take('+')) take('-');
        if (!digit()) throw error("invalid exponent");
        while (digit()) index++;
      }
      if (index - start > 128) throw error("number exceeds limit");
      return new NumberValue(new BigDecimal(input.substring(start, index)));
    }

    private String string() {
      index++;
      var out = new StringBuilder();
      while (index < input.length()) {
        char c = input.charAt(index++);
        if (c == '"') return out.toString();
        if (c < 0x20) throw error("control character in string");
        if (c == '\\') {
          if (index == input.length()) throw error("unfinished escape");
          char escape = input.charAt(index++);
          c =
              switch (escape) {
                case '"', '\\', '/' -> escape;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> unicode();
                default -> throw error("invalid escape");
              };
          if (Character.isHighSurrogate(c)) {
            if (index + 2 > input.length()
                || input.charAt(index++) != '\\'
                || input.charAt(index++) != 'u') throw error("missing low surrogate");
            char low = unicode();
            if (!Character.isLowSurrogate(low)) throw error("invalid low surrogate");
            out.append(c).append(low);
            continue;
          }
        } else if (Character.isHighSurrogate(c)) {
          if (index == input.length() || !Character.isLowSurrogate(input.charAt(index)))
            throw error("unpaired surrogate");
          out.append(c).append(input.charAt(index++));
          continue;
        }
        if (Character.isLowSurrogate(c)) throw error("unpaired surrogate");
        out.append(c);
      }
      throw error("unterminated string");
    }

    private char unicode() {
      if (index + 4 > input.length()) throw error("short unicode escape");
      int code = 0;
      for (int n = 0; n < 4; n++) {
        int digit = Character.digit(input.charAt(index++), 16);
        if (digit < 0 || input.charAt(index - 1) > 127) throw error("invalid unicode escape");
        code = code * 16 + digit;
      }
      return (char) code;
    }

    private boolean literal(String value) {
      if (!input.startsWith(value, index)) return false;
      index += value.length();
      return true;
    }

    private void whitespace() {
      while (index < input.length() && " \t\r\n".indexOf(input.charAt(index)) >= 0) index++;
    }

    private boolean take(char c) {
      if (!at(c)) return false;
      index++;
      return true;
    }

    private boolean at(char c) {
      return index < input.length() && input.charAt(index) == c;
    }

    private boolean digit() {
      return between('0', '9');
    }

    private boolean between(char first, char last) {
      return index < input.length() && input.charAt(index) >= first && input.charAt(index) <= last;
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException("invalid JSON at offset " + index + ": " + message);
    }

    private enum Next {
      FIRST,
      KEY,
      KEY_REQUIRED,
      COLON,
      VALUE,
      AFTER
    }

    private static final class Frame {
      final Map<String, Json> object;
      final List<Json> array;
      String key;
      Next next;

      Frame(boolean isObject) {
        object = isObject ? new LinkedHashMap<>() : null;
        array = isObject ? null : new ArrayList<>();
        next = isObject ? Next.KEY : Next.FIRST;
      }
    }
  }
}
