package io.crewscope.application.task;

import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskTokenAccessRequest;

/** Application boundary for Task Token issuance, authentication, use, rotation and revocation. */
public interface TaskTokenService extends TaskTokenAuthenticator {

    TaskTokenIssueResult issue(TaskTokenIssueCommand command);

    TaskTokenIssueResult rotate(TaskTokenRotateCommand command);

    TaskCredentialGrant authorizeUse(
            String token, TaskTokenAccessRequest request, long expectedGrantVersion);

    TaskCredentialGrant revoke(TaskTokenRevokeCommand command);
}
