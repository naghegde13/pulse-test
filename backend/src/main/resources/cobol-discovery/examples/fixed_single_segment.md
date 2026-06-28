# Fixed Single Segment

Use when every record has the same byte length and the copybook has one dominant 01 layout.

Key options:
- `record_format=F`
- `schema_retention_policy=collapse_root`
- `ebcdic_code_page=cp037`

Typical clue:
- No RDW/BDW header pattern in leading bytes.
