import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard talks to the Spring Boot backend through this proxy, so the backend needs no CORS setup.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
});