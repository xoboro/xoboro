CREATE INDEX catalog_scan_candidate_matched_book_idx
  ON catalog_scan_candidate (session_id, matched_book_id)
  WHERE matched_book_id IS NOT NULL;
