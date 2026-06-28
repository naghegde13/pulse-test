# Variable With RDW

Use when each record starts with a 4-byte RDW length header.

Key options:
- `record_format=V`
- `is_rdw_big_endian=true|false`

Typical clue:
- Fixed parsing fails, but the first bytes look like plausible record sizes.
