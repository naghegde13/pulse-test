"use client";

import { useCallback, useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";

/**
 * Phase 6 contract on the wire:
 *   POST /api/v1/users/me/git-identity            — register
 *   POST /api/v1/users/me/git-identity/rotate     — rotate
 *   DELETE /api/v1/users/me/git-identity          — revoke
 *   GET  /api/v1/users/me/git-identity            — masked status
 *
 * PKT-FINAL-3 (BUG-03/04): the panel collects ONLY the token. GitHub
 * username, author name, author email, and the misplaced repository URL
 * field have all been removed. The backend auto-populates identity from
 * the PAT owner via `GET /user` (and `/user/emails` when scope permits)
 * and the response is displayed read-only below the form.
 */
interface MaskedGitIdentity {
  id: string;
  provider: string;
  credentialType: string;
  credentialReferenceMasked: string | null;
  githubUsername: string | null;
  authorName: string | null;
  authorEmail: string | null;
  scopes: string | null;
  status:
    | "PENDING_VALIDATION"
    | "VALID"
    | "INVALID_TOKEN"
    | "INSUFFICIENT_SCOPE"
    | "REPO_ACCESS_DENIED"
    | "REVOKED"
    | "PROVIDER_UNAVAILABLE"
    | "EXPIRED";
  verifiedAt: string | null;
  lastRotatedAt: string | null;
  revokedAt: string | null;
  lastValidationError: string | null;
}

function statusBadgeClass(status: MaskedGitIdentity["status"]) {
  switch (status) {
    case "VALID":
      return "bg-green-500/10 text-green-700 border-green-500/20";
    case "PENDING_VALIDATION":
      return "bg-yellow-500/10 text-yellow-700 border-yellow-500/20";
    case "REVOKED":
    case "EXPIRED":
      return "bg-gray-500/10 text-gray-700 border-gray-500/20";
    default:
      return "bg-red-500/10 text-red-700 border-red-500/20";
  }
}

/**
 * PKT-FINAL-3 (BUG-08): detect stub mode so we can surface a clear banner
 * instead of asking the operator to debug a fake PROVIDER_UNAVAILABLE.
 */
function isStubModeError(message: string | null): boolean {
  if (!message) return false;
  return message.toLowerCase().includes("stub mode");
}

/**
 * SU-6 / BUG-59: parent surfaces (settings page, onboarding wizard) need to
 * know when the PAT identity has changed so they can re-fetch their own
 * derived `identityStatus` instead of relying on a stale useEffect that
 * runs only on mount. The panel calls {@link Props.onIdentityChanged}
 * exactly once after each successful register / rotate / revoke (after
 * local state has been updated). The callback is optional so existing
 * call sites keep compiling.
 */
export interface GitHubPatIdentityPanelProps {
  onIdentityChanged?: (next: MaskedGitIdentity | null) => void;
}

export function GitHubPatIdentityPanel({
  onIdentityChanged,
}: GitHubPatIdentityPanelProps = {}) {
  const [identity, setIdentity] = useState<MaskedGitIdentity | null>(null);
  const [loading, setLoading] = useState(true);
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState<"register" | "rotate" | "revoke" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const m = await api.get<MaskedGitIdentity>(
        `/api/v1/users/me/git-identity`
      );
      setIdentity(m);
    } catch {
      setIdentity(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const submitRegister = async () => {
    setError(null);
    setBusy("register");
    try {
      // PKT-FINAL-3 (BUG-03): only the token is sent. The backend ignores any
      // additional fields and warns on the server log if legacy clients send
      // them.
      const m = await api.post<MaskedGitIdentity>(
        `/api/v1/users/me/git-identity`,
        { token }
      );
      setIdentity(m);
      setToken("");
      // SU-6 / BUG-59: notify parent so it can re-derive identityStatus.
      onIdentityChanged?.(m);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to register PAT");
    } finally {
      setBusy(null);
    }
  };

  const submitRotate = async () => {
    setError(null);
    setBusy("rotate");
    try {
      const m = await api.post<MaskedGitIdentity>(
        `/api/v1/users/me/git-identity/rotate`,
        { token }
      );
      setIdentity(m);
      setToken("");
      onIdentityChanged?.(m);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to rotate PAT");
    } finally {
      setBusy(null);
    }
  };

  const submitRevoke = async () => {
    setError(null);
    setBusy("revoke");
    try {
      const m = await api.delete<MaskedGitIdentity>(
        `/api/v1/users/me/git-identity`
      );
      setIdentity(m);
      onIdentityChanged?.(m);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to revoke PAT");
    } finally {
      setBusy(null);
    }
  };

  const stubBanner = identity && isStubModeError(identity.lastValidationError);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">GitHub Personal Access Token (classic)</CardTitle>
        <CardDescription>
          Pulse stores your token only in Google Secret Manager. The token is
          never echoed back from the API and is never prefilled here after
          submit. Your GitHub username and author name/email are auto-populated
          from the PAT owner — there are no fields to type.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {stubBanner && (
          <div className="rounded-md border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-800 dark:text-amber-200">
            <strong>GitHub client is in stub mode.</strong> PAT validation will
            fail until <code className="font-mono">pulse.git.github.enabled</code>{" "}
            is set to <code className="font-mono">true</code> on the backend.
            Set <code className="font-mono">PULSE_GIT_GITHUB_ENABLED=true</code>{" "}
            and restart the backend.
          </div>
        )}
        {loading && (
          <div className="h-8 w-full bg-muted animate-pulse rounded" />
        )}
        {!loading && identity && (
          <div className="space-y-2 rounded-md border p-3 text-xs">
            <div className="flex items-center gap-2 flex-wrap">
              <Badge variant="outline" className={statusBadgeClass(identity.status)}>
                {identity.status}
              </Badge>
              {identity.githubUsername && (
                <span className="font-medium">@{identity.githubUsername}</span>
              )}
              {identity.scopes && (
                <Badge variant="outline" className="text-[10px]">
                  scopes: {identity.scopes}
                </Badge>
              )}
              {identity.credentialReferenceMasked && (
                <span className="text-muted-foreground font-mono break-all">
                  {identity.credentialReferenceMasked}
                </span>
              )}
            </div>
            {/* PKT-FINAL-3 (BUG-03): read-only identity readout — the values
                live on the row but were never collected from the operator. */}
            {(identity.authorName || identity.authorEmail) && (
              <dl className="grid grid-cols-3 gap-x-2 gap-y-0.5 text-[11px] pt-1">
                {identity.authorName && (
                  <>
                    <dt className="text-muted-foreground">Author name</dt>
                    <dd className="col-span-2">{identity.authorName}</dd>
                  </>
                )}
                {identity.authorEmail && (
                  <>
                    <dt className="text-muted-foreground">Author email</dt>
                    <dd className="col-span-2 font-mono break-all">
                      {identity.authorEmail}
                    </dd>
                  </>
                )}
              </dl>
            )}
            {identity.lastValidationError && (
              <div className="text-[11px] text-destructive">
                {identity.lastValidationError}
              </div>
            )}
            {identity.lastRotatedAt && (
              <div className="text-[11px] text-muted-foreground">
                Last rotated: {new Date(identity.lastRotatedAt).toLocaleString()}
              </div>
            )}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="pat-token" className="text-xs">
            Personal Access Token (classic)
          </Label>
          <Input
            id="pat-token"
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="ghp_…"
            autoComplete="new-password"
            className="h-8 text-sm"
          />
          <p className="text-[10px] text-muted-foreground">
            Required scopes: <span className="font-mono">repo</span>. Optional:
            <span className="font-mono"> user:email</span> to derive the
            author email. The token is written to Google Secret Manager and
            never persisted in Pulse.
          </p>
        </div>
        {error && <p className="text-xs text-destructive">{error}</p>}
        <div className="flex gap-2 justify-end">
          {identity && (
            <Button
              variant="ghost"
              size="sm"
              onClick={submitRevoke}
              disabled={busy !== null}
            >
              {busy === "revoke" ? "Revoking…" : "Revoke"}
            </Button>
          )}
          {identity ? (
            <Button
              size="sm"
              onClick={submitRotate}
              disabled={busy !== null || !token.trim()}
            >
              {busy === "rotate" ? "Rotating…" : "Rotate"}
            </Button>
          ) : (
            <Button
              size="sm"
              onClick={submitRegister}
              disabled={busy !== null || !token.trim()}
            >
              {busy === "register" ? "Registering…" : "Register"}
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
