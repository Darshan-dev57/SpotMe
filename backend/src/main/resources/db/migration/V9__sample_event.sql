-- The public event anyone can try is called the sample event now.
ALTER TABLE events RENAME COLUMN demo TO sample;

UPDATE organizers SET email = 'sample@spotme.invalid', display_name = 'SpotMe sample'
WHERE email = 'demo@spotme.invalid';

-- new wording for the notice, so visitors read it and agree again
UPDATE events SET title = 'Crew Dragon launch week',
                  purpose_notice = 'This is the SpotMe sample event. The photos are public domain NASA photos of the Crew Dragon launch week in May 2020. Your selfie is compared in memory and never saved. This invite only works for this event and ends when you leave or the event expires.',
                  notice_version = notice_version + 1
WHERE sample;
DELETE FROM photo_alerts WHERE event_id IN (SELECT id FROM events WHERE sample);
UPDATE memberships SET label = 'Visitor'
WHERE label = 'Demo visitor' AND event_id IN (SELECT id FROM events WHERE sample);
