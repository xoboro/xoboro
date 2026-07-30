-- Per-library switch for reading a PDF's document information dictionary into book metadata.
--
-- Additive with a default, so an existing library keeps working without a data migration and the
-- column reads back the same value the domain default would have produced. Defaults to enabled to
-- match every other import switch: a library that holds PDFs almost certainly wants their titles.
ALTER TABLE library ADD COLUMN import_pdf_book INTEGER NOT NULL DEFAULT 1;
