package io.crewscope.domain.collaboration;

import io.crewscope.domain.provider.ProviderCapabilities;

/** Product-owned capability vocabulary for the MVP Lark Collaboration Provider. */
public final class LarkCollaborationCapabilities {

    public static final String CONNECTOR_KEY = "lark-collaboration";
    public static final String MEMBER_LOOKUP = "collaboration.member.lookup-exact";
    public static final String FIXED_TEMPLATE_NOTIFY =
            "collaboration.notification.send-fixed-template";
    public static final ProviderCapabilities MEMBER_MAPPING =
            ProviderCapabilities.of(MEMBER_LOOKUP);
    public static final ProviderCapabilities NOTIFICATION_DELIVERY =
            ProviderCapabilities.of(FIXED_TEMPLATE_NOTIFY);
    public static final ProviderCapabilities COMPLETE =
            ProviderCapabilities.of(MEMBER_LOOKUP, FIXED_TEMPLATE_NOTIFY);

    private LarkCollaborationCapabilities() {}
}
