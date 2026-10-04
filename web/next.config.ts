import type { NextConfig } from "next";

// The UI is a static export: plain HTML/JS files that the Spring Boot app serves in
// production (one origin, no Node server). In development `next dev` runs on :3000
// and calls the API on :8080 directly (CORS is allowed for that origin).
const nextConfig: NextConfig = {
  output: "export",
  images: { unoptimized: true },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
