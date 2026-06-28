# Segment ID Generation

Use when the source is hierarchical and natural join keys are missing between parent and child segments.

Key options:
- `segment_field`
- `segment_id_level0`
- `segment_id_level1`
- `segment_id_level2`

Typical clue:
- Segment filtering works, but downstream reconstruction still needs generated lineage identifiers.
