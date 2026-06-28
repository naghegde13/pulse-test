"use client";

import { useAuth } from "@/contexts/auth-context";
import { LoginForm } from "@/components/login-form";
import { TenantProvider } from "@/contexts/tenant-context";
import { ChatProvider, useChat } from "@/contexts/chat-context";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SidebarProvider } from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/layout/app-sidebar";
import { TopBar } from "@/components/layout/top-bar";
import { ChatPanel } from "@/components/pipeline/chat-panel";

export function AuthGate({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <p className="text-sm text-muted-foreground">Loading...</p>
      </div>
    );
  }

  if (!user) {
    return <LoginForm />;
  }

  return (
    <TenantProvider>
      <ChatProvider>
        <TooltipProvider>
          <SidebarProvider>
            <AppSidebar />
            <main className="min-w-0 flex-1 flex flex-col h-screen overflow-hidden border-l">
              <TopBar />
              <div className="flex-1 overflow-y-auto p-6">{children}</div>
            </main>
            <GlobalChatDrawer />
          </SidebarProvider>
        </TooltipProvider>
      </ChatProvider>
    </TenantProvider>
  );
}

function GlobalChatDrawer() {
  const { isOpen, pipelineId, rightRegionContent } = useChat();
  const open = isOpen || rightRegionContent !== null;

  return (
    <aside
      className={`border-l shadow-[-4px_0_12px_rgba(0,0,0,0.08)] bg-background h-screen overflow-hidden transition-[width] duration-200 ease-in-out ${
        open ? "w-[min(480px,100vw)] max-w-full" : "w-0 border-l-0"
      }`}
    >
      <div className="w-full h-full min-w-0">
        {rightRegionContent ?? <ChatPanel pipelineId={pipelineId ?? undefined} />}
      </div>
    </aside>
  );
}
