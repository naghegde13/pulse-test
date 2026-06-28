# EBCDIC Code Pages

Use when alphabetic/string fields decode with the wrong special characters even though framing looks correct.

Key options:
- `ebcdic_code_page=cp037|cp037_extended|...`

Typical clue:
- Numeric fields look fine, but text contains the wrong symbols or accented characters.
