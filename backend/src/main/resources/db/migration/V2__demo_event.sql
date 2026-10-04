-- Marks the public demo event that any visitor can join (only used when spotme.demo.enabled=true).
ALTER TABLE events ADD COLUMN demo boolean NOT NULL DEFAULT false;
