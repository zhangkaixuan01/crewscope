package io.crewscope.server.api;

import io.crewscope.application.search.SearchCursor;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Versioned opaque keyset cursor for unified search. */
public final class SearchCursorCodec {
  private static final byte VERSION = 1;
  public String encode(SearchCursor cursor) { ByteBuffer b = ByteBuffer.allocate(1 + 8 + 4 + 16).put(VERSION).putLong(cursor.updatedAt().getEpochSecond()).putInt(cursor.updatedAt().getNano()).putLong(cursor.objectId().getMostSignificantBits()).putLong(cursor.objectId().getLeastSignificantBits()); return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array()); }
  public SearchCursor decode(String token) {
    try {
      if (token == null || token.isBlank() || token.length() > 64 || !token.matches("[A-Za-z0-9_-]+")) throw invalid();
      byte[] bytes = Base64.getUrlDecoder().decode(token); if (bytes.length != 29) throw invalid();
      ByteBuffer b = ByteBuffer.wrap(bytes); if (b.get() != VERSION) throw invalid();
      return new SearchCursor(Instant.ofEpochSecond(b.getLong(), b.getInt()), new UUID(b.getLong(), b.getLong()));
    } catch (ApiRequestException e) { throw e; } catch (RuntimeException e) { throw invalid(); }
  }
  private static ApiRequestException invalid() { return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid_cursor", "Cursor is invalid or unsupported", java.util.Map.of("parameter", "after")); }
}
