import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

export default defineConfig(({ mode }) => {
  const workspaceRoot = fileURLToPath(new URL("../../", import.meta.url));
  const env = loadEnv(mode, workspaceRoot, "");
  return {
    envDir: workspaceRoot,
    define: {
      "import.meta.env.VITE_KAKAO_MAPS_JAVASCRIPT_KEY": JSON.stringify(
        env.KAKAO_MAPS_JAVASCRIPT_KEY || "",
      ),
    },
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": { target: "http://127.0.0.1:8080", changeOrigin: true },
        "/actuator": { target: "http://127.0.0.1:8080", changeOrigin: true },
      },
    },
  };
});
