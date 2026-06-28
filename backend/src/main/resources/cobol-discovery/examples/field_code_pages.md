# Field Code Page Overrides

Use when most text decodes correctly but a subset of fields use a different code page.

Key options:
- `ebcdic_code_page`
- `field_code_page:<codepage>`

Typical clue:
- Only specific fields decode into bad characters while the rest of the row looks correct.
