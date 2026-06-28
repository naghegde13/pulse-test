-- BUG-2026-05-28-75: scopes VARCHAR(255) overflow for classic ghp_* PATs
--
-- Classic GitHub PATs (the ghp_* shape, broadest grant) return scope strings
-- via the x-oauth-scopes response header that easily exceed 255 chars when
-- comma-joined. The session-4 throwaway PAT had a 273-char scope string,
-- triggering a DataIntegrityViolationException (varchar(255) overflow) in
-- the wizard's PAT registration path during SU-FINAL-3.
--
-- The original V107 schema's 255-char limit was sized against fine-grained
-- PATs (the github_pat_* shape) which have a tighter scope vocabulary.
-- Widening to TEXT removes the limit; there's no business reason for an
-- arbitrary cap here.
--
-- Forward-only change; no rollback needed (TEXT subsumes VARCHAR(255)).

ALTER TABLE user_git_identities
    ALTER COLUMN scopes TYPE TEXT;
