import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { AuthProvider } from "@/contexts/auth-context";
import { RuntimeAuthorityProvider } from "@/contexts/runtime-authority-context";
import { AuthGate } from "@/components/layout/auth-gate";
import { Toaster } from "sonner";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "PULSE - Pipeline Builder",
  description: "Enterprise GenAI Pipeline Builder",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        <AuthProvider>
          <RuntimeAuthorityProvider>
            <AuthGate>{children}</AuthGate>
            <Toaster position="bottom-right" richColors closeButton duration={1000} />
          </RuntimeAuthorityProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
