-- V65 (M10-F03): the budget alert rides the EXCEPTION inbox lane, so the source-type
-- CHECK gains one member and the notification registry gains its template.
-- The seed ships text variables only: a TRUSTED_LINK inboxUrl would pin an HTTPS
-- origin that a migration cannot know (crewscope.notification.public-base-uri is
-- per deployment). A deployment that wants the link retires v1 and publishes v2
-- with its own origin.

ALTER TABLE crewscope.inbox_item
    DROP CONSTRAINT ck_inbox_item_source_type_v27;

ALTER TABLE crewscope.inbox_item
    ADD CONSTRAINT ck_inbox_item_source_type_v65 CHECK (
        (item_type IN ('OWNERSHIP', 'EXECUTION')
            AND source_type = 'RESPONSIBILITY_ASSIGNMENT')
        OR (item_type = 'REVIEW' AND source_type = 'REVIEW_REQUEST')
        OR (item_type = 'CONFIRMATION' AND source_type = 'ACTION_CONFIRMATION')
        OR (item_type = 'EXCEPTION' AND source_type IN (
            'TASK_EXECUTION', 'ACTION_DELIVERY', 'NOTIFICATION_DELIVERY', 'BUDGET'
        ))
    );

-- Template id = uuid5(NAMESPACE_URL, 'io.crewscope/notification-template/team-budget-alert/v1').
INSERT INTO crewscope.notification_template (
    template_id, template_version, server_template_key, status
) VALUES (
    '88460269-6f59-5eac-9f8e-ad1c71201c6f', 1, 'team-budget-alert', 'PUBLISHED'
);

INSERT INTO crewscope.notification_template_variable (
    template_id, template_version, variable_name,
    variable_type, maximum_length, trusted_origins
) VALUES
    ('88460269-6f59-5eac-9f8e-ad1c71201c6f', 1, 'itemType', 'TEXT', 40, '[]'::JSONB),
    ('88460269-6f59-5eac-9f8e-ad1c71201c6f', 1, 'sourceType', 'TEXT', 40, '[]'::JSONB),
    ('88460269-6f59-5eac-9f8e-ad1c71201c6f', 1, 'sourceId', 'TEXT', 64, '[]'::JSONB),
    ('88460269-6f59-5eac-9f8e-ad1c71201c6f', 1, 'sourceRevision', 'TEXT', 40, '[]'::JSONB),
    ('88460269-6f59-5eac-9f8e-ad1c71201c6f', 1, 'priority', 'TEXT', 16, '[]'::JSONB);
