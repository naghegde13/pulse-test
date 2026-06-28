# National / Alternate String Encodings

Use when the copybook contains `PIC N` or string decoding still looks wrong after standard code-page changes.

Key options:
- `ebcdic_code_page`
- NATIONAL string support in the copybook

Typical clue:
- Layout/framing is correct, but string fields still decode incorrectly for non-basic characters.
