# Variable Blocked (VB)

Use when the file contains BDW + RDW headers.

Key options:
- `record_format=VB`
- `is_bdw_big_endian=true|false`
- `is_rdw_big_endian=true|false`
- `bdw_adjustment` when the BDW includes its own length

Typical clue:
- The file begins with a plausible block length, and each record inside the block has its own RDW.
