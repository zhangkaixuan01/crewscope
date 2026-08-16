package io.crewscope.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal member endpoint used by the controller contract task. */
@RestController
@RequestMapping("/members")
public final class MemberController {

  @PostMapping
  public ResponseEntity<MemberResponse> create(@RequestBody CreateMemberRequest request) {
    return ResponseEntity.ok(new MemberResponse(request.email(), request.displayName()));
  }

  public record CreateMemberRequest(@Email String email, @NotBlank String displayName) {}

  public record MemberResponse(String email, String displayName) {}
}
