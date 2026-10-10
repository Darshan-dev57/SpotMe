import type { Metadata, Viewport } from "next";
import { IBM_Plex_Mono, Schibsted_Grotesk } from "next/font/google";
import { Toaster } from "@/components/ui/sonner";
import "./globals.css";

const body = Schibsted_Grotesk({ subsets: ["latin"], variable: "--font-body", display: "swap" });
const data = IBM_Plex_Mono({ subsets: ["latin"], weight: ["400", "500"], variable: "--font-data", display: "swap" });

export const metadata: Metadata = {
  title: "SpotMe: find your photos from the event",
  description:
    "Organizers upload a private event album. Invited guests take one selfie and get back the photos they are in, from that event only.",
};

export const viewport: Viewport = {
  themeColor: "#f8f5f0",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={`${body.variable} ${data.variable}`}>
      <body className="min-h-dvh">
        {children}
        <Toaster position="top-center" />
      </body>
    </html>
  );
}
