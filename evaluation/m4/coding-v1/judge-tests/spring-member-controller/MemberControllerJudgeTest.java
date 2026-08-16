package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.validation.Valid;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MemberControllerJudgeTest {

  @Test
  void validatesTheBodyAndReturnsCreated() throws Exception {
    Method method =
        MemberController.class.getMethod("create", MemberController.CreateMemberRequest.class);
    assertNotNull(method.getParameters()[0].getAnnotation(Valid.class));

    var response =
        new MemberController()
            .create(new MemberController.CreateMemberRequest("member@example.test", "Member"));
    assertEquals(HttpStatus.CREATED, response.getStatusCode());
  }
}
