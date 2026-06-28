"use client";

/**
 * ChatTableWrapper — wraps a markdown-rendered <table> with sticky
 * first-column + sticky header (CSS) AND a hover-revealed "Expand"
 * button that opens the table in a wide modal.
 *
 * Intentionally has NO logic that walks the children tree — earlier
 * versions tried to count columns to decide whether to show the
 * Expand button, but the children shape varies across react-markdown
 * versions and a single throw blanks the whole chat panel. The
 * Expand button is always rendered; if a small table doesn't need it,
 * the user just doesn't click. Hover-only opacity hides it from the
 * normal reading flow.
 */

import { useState, type HTMLAttributes, type ReactNode } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface ChatTableWrapperProps extends HTMLAttributes<HTMLTableElement> {
  children?: ReactNode;
}

export function ChatTableWrapper({ children, ...props }: ChatTableWrapperProps) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <div className="table-wrapper">
        <button
          type="button"
          className="table-expand-btn"
          onClick={() => setOpen(true)}
          title="Expand table to full width"
        >
          ⤢ Expand
        </button>
        <table {...props}>{children}</table>
      </div>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent
          className="!max-w-[95vw] sm:!max-w-[1400px] w-[95vw] max-h-[90vh] flex flex-col"
        >
          <DialogHeader>
            <DialogTitle className="text-base">Table — full view</DialogTitle>
          </DialogHeader>
          {/* Same chat-markdown class so the modal table inherits the
           * sticky-first-column + sticky-header CSS. The wrapper here
           * gets its own scroll region so the modal's chrome stays put. */}
          <div className="chat-markdown overflow-auto flex-1 -mx-2 px-2">
            <div className="table-wrapper" style={{ maxHeight: "calc(90vh - 7rem)" }}>
              <table {...props}>{children}</table>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
