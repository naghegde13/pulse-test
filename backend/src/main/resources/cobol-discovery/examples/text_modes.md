# Text Modes D / D2

Use when the file is line-delimited text instead of binary EBCDIC framing.

Key options:
- `record_format=D` for line-delimited text
- `record_format=D2` for Cobrix basic ASCII mode
- `encoding=ascii` when the file is not EBCDIC

Typical clue:
- Records are separated by line endings, not binary RDW/BDW framing.
