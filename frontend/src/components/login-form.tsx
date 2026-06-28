"use client";

import Image from "next/image";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useAuth } from "@/contexts/auth-context";

const DEMO_USERS = [
  { email: "builder@pulse.dev", role: "Admin", name: "Dev Builder" },
  { email: "sarah@acme.com", role: "Citizen", name: "Sarah Chen" },
  { email: "mike@acme.com", role: "Data Engineer", name: "Mike Rivera" },
  { email: "priya@acme.com", role: "Deployer", name: "Priya Patel" },
];

export function LoginForm() {
  const { login, error } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    try {
      await login(email, password);
    } catch {
      // error is set via context
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDemoLogin(demoEmail: string) {
    setSubmitting(true);
    try {
      await login(demoEmail, "pulse-admin");
    } catch {
      // error is set via context
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-background">
      <div className="w-full max-w-md space-y-6">
        <div className="text-center space-y-4">
          <Image
            src="/corporate-logo.svg"
            alt="Corporate Logo"
            width={180}
            height={40}
            className="h-10 mx-auto w-auto"
            priority
          />
          <div className="space-y-1">
            <h1 className="text-3xl font-bold tracking-tight">PULSE</h1>
            <p className="text-muted-foreground text-sm">
              Gen Pipeline Builder
            </p>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Sign in</CardTitle>
            <CardDescription>
              Enter your credentials to access PULSE
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="email">Email</Label>
                <Input
                  id="email"
                  type="email"
                  placeholder="you@company.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="password">Password</Label>
                <Input
                  id="password"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>
              {error && (
                <p className="text-sm text-destructive">{error}</p>
              )}
              <Button type="submit" className="w-full" disabled={submitting}>
                {submitting ? "Signing in..." : "Sign in"}
              </Button>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm">Demo accounts</CardTitle>
            <CardDescription className="text-xs">
              Password for all: <code className="text-xs">pulse-admin</code>
            </CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-2">
            {DEMO_USERS.map((u) => (
              <Button
                key={u.email}
                variant="outline"
                size="sm"
                className="h-auto py-2 flex flex-col items-start"
                disabled={submitting}
                onClick={() => handleDemoLogin(u.email)}
              >
                <span className="text-xs font-medium">{u.name}</span>
                <span className="text-[10px] text-muted-foreground">
                  {u.role}
                </span>
              </Button>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
