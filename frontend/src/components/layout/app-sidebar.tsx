"use client";

import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarFooter,
} from "@/components/ui/sidebar";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/contexts/auth-context";
import { useTenant } from "@/contexts/tenant-context";
import { useTenantDomains } from "@/hooks/use-tenant-domains";
import { toDomainOptionValue } from "@/lib/domains";
import Link from "next/link";
import { usePathname } from "next/navigation";

const navItems = [
  { title: "Dashboard", href: "/", icon: "~" },
  { title: "Pipelines", href: "/pipelines", icon: ">" },
  { title: "Data Sources", href: "/producers", icon: "#" },
  { title: "Data Sinks", href: "/targets", icon: "+" },
  { title: "Domains", href: "/domains", icon: "@" },
  { title: "Blueprints", href: "/blueprints", icon: "%" },
  // SESSION-3 BUG-2026-05-26-68: restored sidebar entry for the EBCDIC /
  // COBOL / Copybook Discovery page. Page + backend (com.pulse.cobol) and
  // frontend (app/ebcdic-discovery, components/ebcdic-discovery,
  // contexts/ebcdic-discovery-context) all exist; only the nav link had
  // been dropped, hiding the feature from operators.
  { title: "EBCDIC Discovery", href: "/ebcdic-discovery", icon: "E" },
  { title: "Commands", href: "/commands", icon: "$" },
  { title: "AI Chat", href: "/chat", icon: "?" },
  { title: "Settings", href: "/settings", icon: "*" },
];

export function AppSidebar() {
  const pathname = usePathname();
  const { user } = useAuth();
  const { tenants, currentTenant, setCurrentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);

  return (
    <Sidebar>
      <SidebarHeader className="border-b px-4 py-3 space-y-3">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-md bg-primary text-primary-foreground font-bold text-sm">
            P
          </div>
          <div>
            <p className="text-sm font-semibold">PULSE</p>
            <p className="text-xs text-muted-foreground">Pipeline Builder</p>
          </div>
        </div>

        {tenants.length > 0 && (
          <Select
            value={currentTenant?.id ?? ""}
            onValueChange={(val) => {
              const t = tenants.find((t) => t.id === val);
              if (t) setCurrentTenant(t);
            }}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue placeholder="Select tenant" />
            </SelectTrigger>
            <SelectContent>
              {tenants.map((t) => (
                <SelectItem key={t.id} value={t.id} className="text-xs">
                  {t.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>Navigation</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {navItems.map((item) => (
                <SidebarMenuItem key={item.href}>
                  <SidebarMenuButton
                    asChild
                    isActive={
                      item.href === "/"
                        ? pathname === "/"
                        : pathname.startsWith(item.href)
                    }
                  >
                    <Link href={item.href}>
                      <span className="font-mono text-xs w-4 text-center">
                        {item.icon}
                      </span>
                      <span>{item.title}</span>
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        {currentTenant && (
          <SidebarGroup>
            <SidebarGroupLabel>Domains</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {domainOptions.map((domain) => (
                  <SidebarMenuItem key={toDomainOptionValue(domain)}>
                    <SidebarMenuButton asChild>
                      <Link
                        href={
                          domain.id
                            ? `/pipelines?domain=${encodeURIComponent(domain.id)}`
                            : `/pipelines?domain=${encodeURIComponent(domain.name)}`
                        }
                      >
                        <span className="font-mono text-xs w-4 text-center">
                          /
                        </span>
                        <span className="capitalize">
                          {domain.name}
                          {domain.source === "legacy" ? " (legacy)" : ""}
                        </span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        )}
      </SidebarContent>

      <SidebarFooter className="border-t p-4">
        {user && (
          <div className="flex items-center gap-2">
            <Avatar className="h-7 w-7">
              <AvatarFallback className="text-xs">
                {user.displayName
                  .split(" ")
                  .map((n) => n[0])
                  .join("")}
              </AvatarFallback>
            </Avatar>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">
                {user.displayName}
              </p>
              <Badge variant="secondary" className="text-[10px] px-1 py-0">
                {user.role.replace("_", " ")}
              </Badge>
            </div>
          </div>
        )}
      </SidebarFooter>
    </Sidebar>
  );
}
