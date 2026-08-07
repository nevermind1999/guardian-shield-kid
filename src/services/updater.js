// Service de Auto-Update via GitHub Releases
const GITHUB_REPO = 'nevermind1999/guardian-shield-kid';
const CURRENT_VERSION = '1.0.0';

export async function checkForAppUpdates() {
  try {
    const res = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`, {
      headers: { 'Accept': 'application/vnd.github.v3+json' }
    });

    if (!res.ok) return { hasUpdate: false };

    const release = await res.json();
    const latestVersion = release.tag_name ? release.tag_name.replace('v', '') : '1.0.0';

    if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
      const apkAsset = release.assets?.find(a => a.name.endsWith('.apk'));
      const downloadUrl = apkAsset ? apkAsset.browser_download_url : release.html_url;

      return {
        hasUpdate: true,
        currentVersion: CURRENT_VERSION,
        latestVersion,
        releaseNotes: release.body || 'Melhorias e correções de desempenho.',
        downloadUrl
      };
    }
  } catch (e) {
    console.log('Verificação de atualização ignorada:', e.message);
  }
  return { hasUpdate: false };
}

function isNewerVersion(latest, current) {
  const latestParts = latest.split('.').map(Number);
  const currentParts = current.split('.').map(Number);

  for (let i = 0; i < Math.max(latestParts.length, currentParts.length); i++) {
    const v1 = latestParts[i] || 0;
    const v2 = currentParts[i] || 0;
    if (v1 > v2) return true;
    if (v1 < v2) return false;
  }
  return false;
}
