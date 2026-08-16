package io.crewscope.evaluation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Encodes a resume cursor for one workspace event stream. */
public final class OpaqueCursorCodec {

  public String encode(String workspaceId, long sequence) {
    String payload = workspaceId + ":" + sequence;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  public Cursor decode(String value, String expectedWorkspaceId) {
    String payload = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    String[] fields = payload.split(":", 2);
    return new Cursor(fields[0], Long.parseLong(fields[1]));
  }

  public record Cursor(String workspaceId, long sequence) {}
}
