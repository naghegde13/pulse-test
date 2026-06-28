# Record Length Field

Use when each record contains its own length in a field instead of an RDW header.

Key options:
- `record_format=F`
- `record_length_field=FIELD_NAME`

Typical clue:
- No RDW headers, but one leading field consistently matches the actual record byte size.
