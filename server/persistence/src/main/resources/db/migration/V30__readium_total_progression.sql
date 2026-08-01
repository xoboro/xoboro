-- Restates media_position.total_progression under the Readium convention.
--
-- Analyzed positions were written as `position / n`, the progress at the *end* of a position,
-- which is one position ahead of a Readium locator's `totalProgression`. The analyzer now writes
-- `(position - 1) / n`. Without this statement an existing library would keep serving the old
-- convention from storage while every reader of the column interprets it as the new one, which
-- is worse than the original defect: the two conventions would coexist in one column with
-- nothing to tell them apart.
--
-- Data only. No column is added, dropped, renamed or retyped, so the schema is unchanged and a
-- rollback to the previous application version reads the same shape it wrote. The values
-- themselves are a pure function of `position` and the per-book row count, neither of which this
-- statement touches, so the old convention is recoverable exactly by running the inverse:
--
--   UPDATE media_position SET total_progression =
--     position * 1.0 / (SELECT COUNT(*) FROM media_position older
--                       WHERE older.book_id = media_position.book_id);
--
-- Nothing is lost that would have to be reconstructed from the EPUB files, and re-analysis
-- reproduces the same values either way. The existing CHECK constraint holds: the result is in
-- [0, 1) because `position` is in 1..n.
UPDATE media_position
SET total_progression =
      (position - 1) * 1.0 / (
        SELECT COUNT(*)
        FROM media_position sibling
        WHERE sibling.book_id = media_position.book_id
      );
