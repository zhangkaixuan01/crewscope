-- M10 A02a/A03a: zero-event commands (knowledge and skill draft updates) complete their
-- receipts anchored on the command id — by design no domain event exists for a draft
-- write (drafts stay out of the event stream and its index consumers), so the V5
-- event-existence FK cannot hold for that column. Event-emitting commands keep writing
-- real event ids; ux_command_receipt_domain_event still guarantees one receipt per anchor.
ALTER TABLE crewscope.command_receipt DROP CONSTRAINT fk_command_receipt_domain_event;
