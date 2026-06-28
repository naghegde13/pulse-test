-- V3: Update lifecycle stages for zone-segregated deployment model.
-- Dev/Integration managed by PULSE; UAT/Prod managed by enterprise CD via Artifactory.
-- UAT_APPROVED renamed to PUBLISHED (Artifactory handoff) + UAT_DEPLOYED added.

UPDATE pipelines SET lifecycle_stage = 'PUBLISHED'     WHERE lifecycle_stage = 'UAT_APPROVED';
