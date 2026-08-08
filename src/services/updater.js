// Service de Auto-Update via GitHub Releases e Commits no main
const GITHUB_REPO = 'nevermind1999/guardian-shield-kid';
const CURRENT_BUILD_SHA = '7cfc725'; // SHA da build anterior instalada no celular

export async function checkForAppUpdates() {
  try {
    // 1. Tenta buscar a Release oficial do GitHub
    const releaseRes = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`, {
      headers: { 'Accept': 'application/vnd.github.v3+json' }
    });

    if (releaseRes.ok) {
      const release = await releaseRes.json();
      const latestVersion = release.tag_name ? release.tag_name.replace('v', '') : '1.0.0';
      const apkAsset = release.assets?.find(a => a.name.endsWith('.apk'));

      return {
        hasUpdate: true,
        latestVersion: `v${latestVersion}`,
        releaseNotes: release.body || 'Nova versão com melhorias e correções.',
        downloadUrl: apkAsset ? apkAsset.browser_download_url : release.html_url
      };
    }

    // 2. Se não houver Release oficial ainda, checa o último commit no GitHub
    const commitRes = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/commits/main`, {
      headers: { 'Accept': 'application/vnd.github.v3+json' }
    });

    if (commitRes.ok) {
      const commitData = await commitRes.json();
      const latestSha = commitData.sha ? commitData.sha.substring(0, 7) : '';
      const commitMessage = commitData.commit?.message || 'Atualização recente de código no GitHub.';

      if (latestSha && latestSha !== CURRENT_BUILD_SHA) {
        return {
          hasUpdate: true,
          latestVersion: `Atualização (${latestSha})`,
          releaseNotes: commitMessage,
          downloadUrl: `https://github.com/${GITHUB_REPO}`
        };
      }
    }
  } catch (e) {
    console.log('Checagem de atualização ignorada:', e.message);
  }
  return { hasUpdate: false };
}
