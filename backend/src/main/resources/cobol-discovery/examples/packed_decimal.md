# Packed Decimal / COMP-3

Use when numeric values are encoded with packed decimal or binary storage.

Key options:
- correct `ebcdic_code_page`
- verify `COMP`, `COMP-3`, `COMP-1`, `COMP-2`, `COMP-5`
- use `debug=true` if values decode as garbage

Typical clue:
- String fields look correct but numeric fields are nonsensical.
