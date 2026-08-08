// Service de Auto-Update Direto via Servidor em Nuvem e GitHub
const GITHUB_REPO = 'nevermind1999/guardian-shield-kid';
const DIRECT_APK_URL = 'https://guardian-shield.oguiazevedo.com/api/download/filho';
const CURRENT_BUILD_SHA = '30134e4';

export async function checkForAppUpdates() {
  try {
    const commitRes = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/commits/main`, {
      headers: { 'Accept': 'application/vnd.github.v3+json' }
    });

    if (commitRes.ok) {
      const commitData = await commitRes.json();
      const latestSha = commitData.sha ? commitData.sha.substring(0, 7) : '';
      const commitMessage = commitData.commit?.message || 'Melhorias de desempenho e estabilidade.';
      const dismissedSha = localStorage.getItem('dismissed_update_sha');

      if (latestSha && latestSha !== CURRENT_BUILD_SHA && dismissedSha !== latestSha) {
        return {
          hasUpdate: true,
          latestVersion: `Build ${latestSha}`,
          latestSha: latestSha,
          releaseNotes: commitMessage,
          downloadUrl: DIRECT_APK_URL
        };
      }
    }
  } catch (e) {
    console.log('Checagem de atualização ignorada:', e.message);
  }
  return { hasUpdate: false };
}
