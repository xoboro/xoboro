-- Every remaining write to a search index still finds its row by reading the whole index.
--
-- V35 removed the inserts' scans by observing that they deleted nothing. Updates and deletes cannot
-- be dismissed that way - they really do have a row to remove - so the lookup itself has to get
-- cheaper. `entity_type` and `entity_id` are UNINDEXED in both FTS5 tables, which is not an oversight
-- (FTS5 offers no other way to carry a column it must not tokenise); it does mean `WHERE entity_type
-- = ? AND entity_id = ?` can only be answered by `SCAN ... VIRTUAL TABLE INDEX 0`.
--
-- What FTS5 can seek is its rowid. So each entity is given one, kept here, and both indexes store the
-- entity's row under it. A delete becomes `WHERE rowid = ?` against a b-tree instead of a pass over
-- the catalogue.
--
-- This matters most where V31 left off. V31 stopped `catalog_search_book_update` from firing on a
-- write that changed nothing, which is what made a re-scan affordable; it did not make the firing
-- cheaper. That trigger runs once per updated row, so a re-scan that genuinely changes m books still
-- paid m passes over the index. Keyed, each of those is a seek.
--
-- One key table serves both indexes. Neither holds more than one row per entity, so one number
-- addresses the entity in both, and a single allocation keeps the two from disagreeing about which
-- row belongs to whom.
--
-- Plain `INTEGER PRIMARY KEY`, deliberately not `AUTOINCREMENT`. V34 needed monotonicity because a
-- reused cursor value would skip a client past changes it never saw. Nothing here reads the number:
-- a key and the index rows under it are removed by the same trigger body, so a reused rowid can only
-- ever be reused after everything stored under it is gone.

CREATE TABLE catalog_search_key (
  index_rowid INTEGER PRIMARY KEY,
  entity_type TEXT NOT NULL CHECK (entity_type IN ('BOOK', 'SERIES')),
  entity_id   TEXT NOT NULL,
  CONSTRAINT catalog_search_key_entity_not_blank CHECK (length(trim(entity_id)) > 0),
  CONSTRAINT catalog_search_key_entity_unique UNIQUE (entity_type, entity_id)
) STRICT;

-- The word index is not rebuilt. An FTS5 row's rowid cannot be changed, but nothing says the key has
-- to be a new number - so the key table adopts the rowids the index already uses, and the largest
-- table in the database is never rewritten.
--
-- Rebuilding it instead measured **52.2 s** on a snapshot of the deployed catalogue (145,105 books,
-- 3,339 series, 4.55 GB), and that is a stall in front of every request while the process starts.
-- Almost none of it is FTS5: rebuilding one row evaluates `catalog_book_search_source`, three joins
-- and five correlated `group_concat` subqueries, once per book. The interior-match index is rebuilt
-- below because its rowids are its own and cannot both be adopted, but its source view carries one
-- subquery instead of five, which is why V32 built it in 1.08 s over the same 148,444 rows.
--
-- What is given up: a rebuild would also have repaired any drift between the stored index and what
-- the views produce today. There was none to repair - a full rebuild on the snapshot returned both
-- digests unchanged to the byte-length - and a fifty-second stall to fix nothing is the worse trade.
-- Anything that does drift is already reachable by the metadata rebuild path per entity.
--
-- That leaves the two indexes asymmetric on one point, worth knowing before debugging a search:
-- **an entity already missing from the word index stays missing**, because it has no rowid to adopt
-- and nothing rebuilds it, while the interior-match index below is rebuilt from the views and comes
-- out complete. The symptom is an entity findable by fragment but not by word. This migration does
-- not create that state - the deployed catalogue has 148,444 rows for 148,444 entities, one each -
-- and `rebuildBookSearchDocument` repairs one entity when it does happen.
--
-- `GROUP BY` rather than a plain select, and the delete below, because an entity with *two* rows
-- would otherwise try to claim its key twice and fail the `UNIQUE` constraint - aborting the
-- migration and leaving the server unable to start, on a database whose only fault is one duplicate
-- row. There is no duplicate in the deployed catalogue (148,444 rows for 148,444 entities) and the
-- triggers this replaces all deleted before inserting, so this is defence rather than repair.
--
-- Dropping the unclaimed copy is not tidying. Deleting by entity took every row an entity had, so a
-- duplicate healed itself on the next write; deleting by rowid takes one, so a duplicate that
-- survived this migration would survive every write after it and show up as a repeated search
-- result forever. Removing it here is what keeps that from becoming permanent.
INSERT INTO catalog_search_key (index_rowid, entity_type, entity_id)
SELECT min(rowid), entity_type, entity_id
FROM catalog_search_fts
GROUP BY entity_type, entity_id;

DELETE FROM catalog_search_fts
WHERE rowid NOT IN (SELECT index_rowid FROM catalog_search_key);

-- An entity the word index never held still needs a key, or the triggers below would join against
-- nothing and leave it silently unindexed. These take numbers above every adopted one, because
-- `INTEGER PRIMARY KEY` allocates `max + 1`, so no new key can collide with an index row already
-- sitting on that rowid.
INSERT INTO catalog_search_key (entity_type, entity_id)
SELECT 'BOOK', book.id FROM book
WHERE NOT EXISTS (
  SELECT 1 FROM catalog_search_key existing
  WHERE existing.entity_type = 'BOOK' AND existing.entity_id = book.id
);
INSERT INTO catalog_search_key (entity_type, entity_id)
SELECT 'SERIES', series.id FROM series
WHERE NOT EXISTS (
  SELECT 1 FROM catalog_search_key existing
  WHERE existing.entity_type = 'SERIES' AND existing.entity_id = series.id
);

DELETE FROM catalog_title_substring;
INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
SELECT search_key.index_rowid, 'BOOK', source.entity_id, source.title
FROM catalog_book_title_source source
JOIN catalog_search_key search_key
  ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id;
INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
SELECT search_key.index_rowid, 'SERIES', source.entity_id, source.title
FROM catalog_series_title_source source
JOIN catalog_search_key search_key
  ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id;

-- Every insert allocates the key it is about to write under, rather than trusting some other trigger
-- to have done it. SQLite leaves the order of two `AFTER INSERT` triggers on one table undefined, and
-- the word index and the interior-match index are maintained by separate triggers by design (V32);
-- `ON CONFLICT DO NOTHING` lets both ask for the key and lets whichever runs first win. It also keeps
-- the join total: without the key, the `INSERT ... SELECT` below would match nothing and leave the
-- entity silently unindexed.
--
-- Writing the rowid explicitly has a second effect worth keeping: indexing one entity twice now fails
-- on the rowid instead of quietly storing a duplicate that only surfaces as a repeated search result.

DROP TRIGGER catalog_search_book_metadata_insert;
CREATE TRIGGER catalog_search_book_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  INSERT INTO catalog_search_key (entity_type, entity_id)
  VALUES ('BOOK', NEW.book_id)
  ON CONFLICT (entity_type, entity_id) DO NOTHING;
  INSERT INTO catalog_search_fts (
    rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT
    search_key.index_rowid, 'BOOK', source.entity_id, source.title, source.summary,
    source.contributors, source.labels, source.identifiers
  FROM catalog_book_search_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.book_id;
END;

DROP TRIGGER catalog_title_book_metadata_insert;
CREATE TRIGGER catalog_title_book_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  INSERT INTO catalog_search_key (entity_type, entity_id)
  VALUES ('BOOK', NEW.book_id)
  ON CONFLICT (entity_type, entity_id) DO NOTHING;
  INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
  SELECT search_key.index_rowid, 'BOOK', source.entity_id, source.title
  FROM catalog_book_title_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.book_id;
END;

DROP TRIGGER catalog_search_series_metadata_insert;
CREATE TRIGGER catalog_search_series_metadata_insert
AFTER INSERT ON series_metadata
BEGIN
  INSERT INTO catalog_search_key (entity_type, entity_id)
  VALUES ('SERIES', NEW.series_id)
  ON CONFLICT (entity_type, entity_id) DO NOTHING;
  INSERT INTO catalog_search_fts (
    rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT
    search_key.index_rowid, 'SERIES', source.entity_id, source.title, source.summary,
    source.contributors, source.labels, source.identifiers
  FROM catalog_series_search_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.series_id;
END;

DROP TRIGGER catalog_title_series_metadata_insert;
CREATE TRIGGER catalog_title_series_metadata_insert
AFTER INSERT ON series_metadata
BEGIN
  INSERT INTO catalog_search_key (entity_type, entity_id)
  VALUES ('SERIES', NEW.series_id)
  ON CONFLICT (entity_type, entity_id) DO NOTHING;
  INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
  SELECT search_key.index_rowid, 'SERIES', source.entity_id, source.title
  FROM catalog_series_title_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.series_id;
END;

-- V31's guard is carried through unchanged. It decides *whether* a rebuild runs; the key decides what
-- it costs when it does. Dropping either one puts back a cost the other cannot cover.

DROP TRIGGER catalog_search_book_update;
CREATE TRIGGER catalog_search_book_update
AFTER UPDATE OF name, series_id ON book
WHEN NEW.name IS NOT OLD.name OR NEW.series_id IS NOT OLD.series_id
BEGIN
  DELETE FROM catalog_search_fts
  WHERE rowid = (
    SELECT index_rowid FROM catalog_search_key
    WHERE entity_type = 'BOOK' AND entity_id = NEW.id
  );
  INSERT INTO catalog_search_key (entity_type, entity_id)
  VALUES ('BOOK', NEW.id)
  ON CONFLICT (entity_type, entity_id) DO NOTHING;
  INSERT INTO catalog_search_fts (
    rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT
    search_key.index_rowid, 'BOOK', source.entity_id, source.title, source.summary,
    source.contributors, source.labels, source.identifiers
  FROM catalog_book_search_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.id;
END;

DROP TRIGGER catalog_title_book_update;
CREATE TRIGGER catalog_title_book_update
AFTER UPDATE OF name, series_id ON book
WHEN NEW.name IS NOT OLD.name OR NEW.series_id IS NOT OLD.series_id
BEGIN
  DELETE FROM catalog_title_substring
  WHERE rowid = (
    SELECT index_rowid FROM catalog_search_key
    WHERE entity_type = 'BOOK' AND entity_id = NEW.id
  );
  INSERT INTO catalog_search_key (entity_type, entity_id)
  VALUES ('BOOK', NEW.id)
  ON CONFLICT (entity_type, entity_id) DO NOTHING;
  INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
  SELECT search_key.index_rowid, 'BOOK', source.entity_id, source.title
  FROM catalog_book_title_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.id;
END;

-- Renaming a series rewrites its books' rows too, and that part stays a set operation: a trigger
-- cannot loop, so the books are addressed as one `rowid IN (...)`. That is one pass over the index
-- rather than one per book, which is what it was before - the gain here is on the single-entity
-- statements around it, not on this one.

DROP TRIGGER catalog_search_series_update;
CREATE TRIGGER catalog_search_series_update
AFTER UPDATE OF name ON series
WHEN NEW.name IS NOT OLD.name
BEGIN
  DELETE FROM catalog_search_fts
  WHERE rowid IN (
    SELECT search_key.index_rowid
    FROM catalog_search_key search_key
    WHERE (search_key.entity_type = 'SERIES' AND search_key.entity_id = NEW.id)
       OR (
         search_key.entity_type = 'BOOK'
         AND search_key.entity_id IN (SELECT id FROM book WHERE series_id = NEW.id)
       )
  );
  INSERT INTO catalog_search_fts (
    rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT
    search_key.index_rowid, 'SERIES', source.entity_id, source.title, source.summary,
    source.contributors, source.labels, source.identifiers
  FROM catalog_series_search_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.id;
  INSERT INTO catalog_search_fts (
    rowid, entity_type, entity_id, title, summary, contributors, labels, identifiers
  )
  SELECT
    search_key.index_rowid, 'BOOK', source.entity_id, source.title, source.summary,
    source.contributors, source.labels, source.identifiers
  FROM catalog_book_search_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id IN (SELECT id FROM book WHERE series_id = NEW.id);
END;

DROP TRIGGER catalog_title_series_update;
CREATE TRIGGER catalog_title_series_update
AFTER UPDATE OF name ON series
WHEN NEW.name IS NOT OLD.name
BEGIN
  DELETE FROM catalog_title_substring
  WHERE rowid IN (
    SELECT search_key.index_rowid
    FROM catalog_search_key search_key
    WHERE (search_key.entity_type = 'SERIES' AND search_key.entity_id = NEW.id)
       OR (
         search_key.entity_type = 'BOOK'
         AND search_key.entity_id IN (SELECT id FROM book WHERE series_id = NEW.id)
       )
  );
  INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
  SELECT search_key.index_rowid, 'SERIES', source.entity_id, source.title
  FROM catalog_series_title_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'SERIES' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id = NEW.id;
  INSERT INTO catalog_title_substring (rowid, entity_type, entity_id, title)
  SELECT search_key.index_rowid, 'BOOK', source.entity_id, source.title
  FROM catalog_book_title_source source
  JOIN catalog_search_key search_key
    ON search_key.entity_type = 'BOOK' AND search_key.entity_id = source.entity_id
  WHERE source.entity_id IN (SELECT id FROM book WHERE series_id = NEW.id);
END;

-- The two indexes were maintained by separate triggers so that neither had to transcribe the other's
-- body (V32). Deletion is the one place that cannot stay split: both index rows and the key that
-- addresses them have to go together, and two triggers whose order SQLite does not define cannot
-- agree on who removes the key last. Whoever ran second would find no key, resolve `rowid = NULL`,
-- and leave its row behind forever. One body, three statements, in an order that is written down.

DROP TRIGGER catalog_search_book_delete;
DROP TRIGGER catalog_title_book_delete;
CREATE TRIGGER catalog_search_book_delete
AFTER DELETE ON book
BEGIN
  DELETE FROM catalog_search_fts
  WHERE rowid = (
    SELECT index_rowid FROM catalog_search_key
    WHERE entity_type = 'BOOK' AND entity_id = OLD.id
  );
  DELETE FROM catalog_title_substring
  WHERE rowid = (
    SELECT index_rowid FROM catalog_search_key
    WHERE entity_type = 'BOOK' AND entity_id = OLD.id
  );
  DELETE FROM catalog_search_key WHERE entity_type = 'BOOK' AND entity_id = OLD.id;
END;

DROP TRIGGER catalog_search_series_delete;
DROP TRIGGER catalog_title_series_delete;
CREATE TRIGGER catalog_search_series_delete
AFTER DELETE ON series
BEGIN
  DELETE FROM catalog_search_fts
  WHERE rowid = (
    SELECT index_rowid FROM catalog_search_key
    WHERE entity_type = 'SERIES' AND entity_id = OLD.id
  );
  DELETE FROM catalog_title_substring
  WHERE rowid = (
    SELECT index_rowid FROM catalog_search_key
    WHERE entity_type = 'SERIES' AND entity_id = OLD.id
  );
  DELETE FROM catalog_search_key WHERE entity_type = 'SERIES' AND entity_id = OLD.id;
END;
