package io.crewscope.application.task;

import io.crewscope.domain.task.TaskTokenClaims;

/** Trusted signing and signature-verification Port for short-lived Task Tokens. */
public interface TaskTokenCodec {

    String encode(TaskTokenClaims claims);

    DecodedTaskToken decode(String token);
}
