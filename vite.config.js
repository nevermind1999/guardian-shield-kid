import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { execSync } from 'child_process';

// Grava o SHA real do commit atual no momento do build, para o serviço de
// auto-update comparar contra a versão de fato instalada (em vez de uma
// string fixa desatualizada no código-fonte).
function getBuildSha() {
  try {
    return execSync('git rev-parse --short HEAD').toString().trim();
  } catch (e) {
    return 'dev';
  }
}

export default defineConfig({
  plugins: [react()],
  define: {
    __BUILD_SHA__: JSON.stringify(getBuildSha())
  },
  server: {
    port: 3002
  }
});
