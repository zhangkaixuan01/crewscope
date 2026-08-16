package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class OpaqueCursorCodecJudgeTest {

  @Test
  void roundTripsOnlyForTheExpectedWorkspace() {
    OpaqueCursorCodec codec = new OpaqueCursorCodec();
    String cursor = codec.encode("workspace-a", 42);
    assertEquals(42, codec.decode(cursor, "workspace-a").sequence());
    assertThrows(IllegalArgumentException.class, () -> codec.decode(cursor, "workspace-b"));
  }

  @Test
  void rejectsTamperingAndMalformedInput() {
    OpaqueCursorCodec codec = new OpaqueCursorCodec();
    String cursor = codec.encode("workspace-a", 42);
    byte[] decoded = Base64.getUrlDecoder().decode(cursor);
    decoded[0] ^= 1;
    String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(tampered, "workspace-a"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("%%%", "workspace-a"));
  }
}
