-- "Tell me about new photos": a guest opts in after a search. Their face vector is kept for this
-- only, one per guest, until the event ends, they turn it off, withdraw, or the notice changes.
-- The private link's token is stored as a hash, like the invite token.
CREATE TABLE photo_alerts (
    id            uuid PRIMARY KEY,
    membership_id uuid NOT NULL UNIQUE REFERENCES memberships (id) ON DELETE CASCADE,
    event_id      uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    token_hash    text NOT NULL UNIQUE,
    embedding     vector(128) NOT NULL,
    model_version text NOT NULL,
    -- photos indexed after this moment count as new
    seen_until    timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now()
);
