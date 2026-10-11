-- The photo ids a guest's latest search returned (never the selfie or its vector), so a download
-- can be checked against what the guest was actually shown. Replaced on every search and deleted
-- when the guest withdraws, is removed, or the event is purged.
CREATE TABLE guest_matches (
    membership_id uuid NOT NULL REFERENCES memberships (id) ON DELETE CASCADE,
    photo_id      uuid NOT NULL REFERENCES photos (id) ON DELETE CASCADE,
    PRIMARY KEY (membership_id, photo_id)
);
