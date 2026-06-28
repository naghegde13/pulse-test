# Custom Record Extractor

Use when the file framing is non-standard and Cobrix built-in record formats do not align the preview correctly.

Key options:
- `record_extractor=<fully.qualified.ClassName>`
- `re_additional_info=<string>` when the extractor needs extra runtime hints

Typical clue:
- Built-in `F`, `V`, `VB`, `FB`, `D`, and `D2` all fail, but the copybook itself still looks plausible.
