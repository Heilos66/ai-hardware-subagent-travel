package com.heilos.travel;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

/** Streaming byte decoder: bounds unterminated lines too, before UTF-8 decoding. */
final class SseDecoder {
  static final int MAX_EVENT_BYTES = 1024 * 1024;

  record Event(String name, String data) {}

  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private final List<String> data = new ArrayList<>();
  private String name = "message";
  private boolean firstLine = true, afterCr;
  private int eventBytes;

  static Flux<Event> decode(Flux<? extends DataBuffer> body) {
    return Flux.defer(
        () -> {
          SseDecoder decoder = new SseDecoder();
          return body.concatMapIterable(decoder::feed, 1)
              .concatWith(Flux.defer(() -> Flux.fromIterable(decoder.finish())))
              .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        });
  }

  private List<Event> feed(DataBuffer buffer) {
    List<Event> events = new ArrayList<>();
    try {
      while (buffer.readableByteCount() > 0) {
        int value = buffer.read() & 255;
        if (afterCr && value == '\n') {
          afterCr = false;
          continue;
        }
        afterCr = value == '\r';
        if (value == '\r' || value == '\n') acceptLine(events);
        else {
          if (++eventBytes > MAX_EVENT_BYTES) throw AgentException.model("模型流式事件过大");
          line.write(value);
        }
      }
      return events;
    } finally {
      DataBufferUtils.release(buffer);
    }
  }

  private void acceptLine(List<Event> events) {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(line.toByteArray()))
              .toString();
    } catch (CharacterCodingException e) {
      throw AgentException.model("模型响应不是有效UTF-8");
    }
    line.reset();
    if (firstLine) {
      firstLine = false;
      if (text.startsWith("\ufeff")) text = text.substring(1);
    }
    if (text.isEmpty()) {
      emit(events);
      return;
    }
    if (text.startsWith(":")) {
      eventBytes -= text.getBytes(StandardCharsets.UTF_8).length;
      return;
    }
    int colon = text.indexOf(':');
    String field = colon < 0 ? text : text.substring(0, colon);
    String value = colon < 0 ? "" : text.substring(colon + 1);
    if (value.startsWith(" ")) value = value.substring(1);
    if (field.equals("data")) data.add(value);
    else if (field.equals("event")) name = value;
  }

  private void emit(List<Event> events) {
    if (!data.isEmpty() || name.equals("error"))
      events.add(new Event(name, String.join("\n", data)));
    data.clear();
    name = "message";
    eventBytes = 0;
  }

  private List<Event> finish() {
    List<Event> events = new ArrayList<>();
    if (line.size() > 0) acceptLine(events);
    emit(events);
    return events;
  }
}
