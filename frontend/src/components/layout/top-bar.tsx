"use client";

import Image from "next/image";
import { SidebarTrigger } from "@/components/ui/sidebar";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/contexts/auth-context";
import { useChat } from "@/contexts/chat-context";

interface TopBarProps {
  title?: string;
}

export function TopBar({ title }: TopBarProps) {
  const { user, logout } = useAuth();
  const { isOpen, open } = useChat();

  return (
    <header className="flex h-12 items-center gap-2 border-b px-4">
      <SidebarTrigger />
      <Separator orientation="vertical" className="h-5" />
      <Image
        src="/corporate-logo.svg"
        alt="Corporate Logo"
        width={120}
        height={20}
        className="h-5 w-auto"
        priority
      />
      <Separator orientation="vertical" className="h-5" />
      {title && <h1 className="text-sm font-medium">{title}</h1>}
      <div className="ml-auto flex items-center gap-3">
        {!isOpen && (
          <Button variant="outline" size="sm" onClick={() => open()}>
            AI Assistant
          </Button>
        )}
        {user && (
          <>
            <span className="text-xs text-muted-foreground">
              {user.displayName}
            </span>
            <Badge variant="outline" className="text-[10px]">
              {user.role.replace("_", " ")}
            </Badge>
            <Button variant="ghost" size="sm" onClick={logout}>
              Sign out
            </Button>
          </>
        )}
      </div>
    </header>
  );
}
