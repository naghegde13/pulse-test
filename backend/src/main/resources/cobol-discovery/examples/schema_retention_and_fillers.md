# Schema Retention / Fillers

Use when the user needs a flatter top-level schema or needs filler/debug fields during discovery.

Key options:
- `schema_retention_policy=collapse_root|keep_original`
- `drop_group_fillers`
- `drop_value_fillers`
- `debug=true`

Typical clue:
- The parse is structurally correct, but the exposed schema or filler handling is not what the user expects.
