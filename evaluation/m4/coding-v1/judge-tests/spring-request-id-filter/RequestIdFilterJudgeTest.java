package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterJudgeTest {

  @Test
  void preservesAValidIdentifier() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "req_0123456789abcdef");
    MockHttpServletResponse response = new MockHttpServletResponse();
    new RequestIdFilter().doFilterInternal(request, response, new MockFilterChain());
    assertEquals("req_0123456789abcdef", response.getHeader("X-Request-Id"));
  }

  @Test
  void generatesAnIdentifierForMissingOrUntrustedInput() throws Exception {
    for (String supplied : new String[] {null, " attacker supplied ", "x".repeat(200)}) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      if (supplied != null) {
        request.addHeader("X-Request-Id", supplied);
      }
      MockHttpServletResponse response = new MockHttpServletResponse();
      new RequestIdFilter().doFilterInternal(request, response, new MockFilterChain());
      String generated = response.getHeader("X-Request-Id");
      assertNotNull(generated);
      assertTrue(generated.matches("req_[0-9a-f]{32}"));
    }
  }
}
