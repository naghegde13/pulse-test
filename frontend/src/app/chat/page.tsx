"use client";

import { useEffect } from "react";
import { useChat } from "@/contexts/chat-context";

export default function ChatPage() {
  const { isOpen, open } = useChat();

  useEffect(() => {
    if (!isOpen) open();
  }, [isOpen, open]);

  return (
    <div className="flex items-center justify-center h-[calc(100vh-8rem)]">
      <p className="text-sm text-muted-foreground">
        The AI Assistant panel is open on the right. You can toggle it from any page using the button in the top bar.
      </p>
    </div>
  );
}
