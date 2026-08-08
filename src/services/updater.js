// Service de Auto-Update Direto via Servidor em Nuvem e GitHub
const GITHUB_REPO = 'nevermind1999/guardian-shield-kid';
const DIRECT_APK_URL = 'https://guardian-shield.oguiazevedo.com/apks/GuardianShield-Filho.apk';
const CURRENT_BUILD_SHA = 'cd7671d';

export async function checkForAppUpdates() {
  try {
    // Checa commits recentes no GitHub
    const commitRes = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/commits/main`, {
      headers: { 'Accept': 'application/vnd.github.v3+json' }
    });

    if (commitRes.ok) {
      const commitData = await commitRes.json();
      const latestSha = commitData.sha ? commitData.sha.substring(0, 7) : '';
      const commitMessage = commitData.commit?.message || 'Melhorias de desempenho e estabilidade.';

      if (latestSha && latestSha !== CURRENT_BUILD_SHA) {
        return {
          hasUpdate: true,
          latestVersion: `Build ${latestSha}`,
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
