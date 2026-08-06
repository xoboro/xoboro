-- Sidecar artwork was stored at the safety ceiling for untrusted input - 1600px, per ADR 0035 - and
-- then served to a grid whose cells are 120-140px wide. On the deployed catalogue that meant 3,338 of
-- 3,339 series sent 85.8 KB and a 1600x2300 decode per cell where 300px was drawn, against 16.9 KB
-- for a generated cover, which arrives already at the configured thumbnail size. The ceiling was doing
-- duty as a display size, and the two are not the same decision.
--
-- Reducing a sidecar to display size is only safe if the full-size image survives somewhere, and it
-- does: the file is still on disk. This records which file, so it can be found again.
--
-- A name, not a path. A sidecar is read back through the same source access that found it, which is
-- given the owning library's root and the owner's source item id - both already known from the owner -
-- so the name is the only part not derivable, and every filesystem path stays inside the access that
-- validates against the library root. A column holding an absolute path would move that decision into
-- a database row.
--
-- Existing rows keep their 1600px bytes and get no name: nothing here can resize a JPEG, and deleting
-- the rows would leave those series with no cover until a refresh ran. A local-artwork refresh replaces
-- them at display size and records the name then.
ALTER TABLE artwork_thumbnail ADD COLUMN source_name TEXT;

-- Only a sidecar has a source file. Generated artwork is derived from a page and an upload's stored
-- copy is the only one there is, so a name on either would name a file that does not exist.
CREATE INDEX artwork_thumbnail_source_idx
  ON artwork_thumbnail (owner_kind, owner_id, source_name)
  WHERE source_name IS NOT NULL;
