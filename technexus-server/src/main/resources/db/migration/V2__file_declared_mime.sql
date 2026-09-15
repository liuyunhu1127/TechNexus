ALTER TABLE tn_file_object
  ADD COLUMN declared_mime VARCHAR(127) NOT NULL AFTER object_key;
