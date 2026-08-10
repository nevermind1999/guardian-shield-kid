package com.guardianshield.child;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.guardianshield.child.services.LockOverlayService;
import com.guardianshield.child.services.ParentalAccessibilityService;
import com.guardianshield.child.util.GuardianPrefs;
import java.util.HashSet;
import java.util.Set;

/**
 * Este Activity é o app Capacitor/React de pareamento e configurações. A tela inicial
 * (Home) e a gaveta de apps do aparelho são nativas — veja LauncherHomeActivity e
 * LauncherDrawerActivity — por isso este Activity NÃO tem mais o intent-filter de HOME.
 */
public class MainActivity extends BridgeActivity {
    public static boolean isPauseAllActive = false;

    // ID do download de atualização em andamento via UpdaterModule (DownloadManager),
    // usado pra reconhecer o aviso de conclusão no updateDownloadReceiver abaixo.
    private long pendingUpdateDownloadId = -1L;
    private BroadcastReceiver updateDownloadReceiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PauseModule.class);
        registerPlugin(LauncherModule.class);
        registerPlugin(UpdaterModule.class);
        super.onCreate(savedInstanceState);

        // Inicializa o estado persistente do SharedPreferences
        SharedPreferences prefs = getSharedPreferences("GuardianShieldPrefs", MODE_PRIVATE);
        isPauseAllActive = prefs.getBoolean("isPauseAllActive", false);

        if (this.bridge != null && this.bridge.getWebView() != null) {
            this.bridge.getWebView().setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        // Avisa quando o APK de atualização (baixado pelo UpdaterModule) termina de
        // baixar, pra abrir a tela de instalação sozinho — nenhum navegador entra
        // nesse fluxo, então o botão voltar nunca sai do app por causa dele.
        updateDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long finishedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (finishedId != -1L && finishedId == pendingUpdateDownloadId) {
                    promptInstallDownloadedUpdate();
                }
            }
        };
        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, downloadFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(updateDownloadReceiver, downloadFilter);
        }
    }

    @Override
    public void onDestroy() {
        if (updateDownloadReceiver != null) {
            try {
                unregisterReceiver(updateDownloadReceiver);
            } catch (Exception ignored) {
                // já desregistrado (ex: onCreate nunca chegou a registrar)
            }
        }
        super.onDestroy();
    }

    /**
     * Abre a tela nativa "Instalar atualização?" pro APK que o UpdaterModule acabou
     * de baixar. Se o app ainda não tem permissão de instalar fontes desconhecidas,
     * manda o usuário conceder uma vez em vez de falhar silenciosamente — o próximo
     * toque em "Baixar e Atualizar" completa a instalação normalmente.
     */
    private void promptInstallDownloadedUpdate() {
        runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                    Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                    permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(permissionIntent);
                    return;
                }
                DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                Uri downloadedFileUri = downloadManager.getUriForDownloadedFile(pendingUpdateDownloadId);
                if (downloadedFileUri == null) return;
                // getUriForDownloadedFile() já devolve um content:// gerenciado pelo próprio
                // DownloadManager (com permissão de leitura própria), então não precisa do
                // FileProvider do app aqui.
                Intent installIntent = new Intent(Intent.ACTION_VIEW);
                installIntent.setDataAndType(downloadedFileUri, "application/vnd.android.package-archive");
                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(installIntent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Verifica se o ParentalAccessibilityService está de fato habilitado pelo usuário
     * em Configurações > Acessibilidade (passo manual que o app não pode ativar sozinho).
     */
    public static boolean isAccessibilityServiceEnabled(Context context) {
        String expectedComponent = context.getPackageName() + "/" + ParentalAccessibilityService.class.getName();
        ContentResolver resolver = context.getContentResolver();
        String enabledServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        for (String component : enabledServices.split(":")) {
            if (component.equalsIgnoreCase(expectedComponent)) {
                return true;
            }
        }
        return false;
    }

    public void updateLockState(boolean active) {
        isPauseAllActive = active;

        // Persiste o estado no SharedPreferences para o AccessibilityService e a Home nativa lerem
        SharedPreferences prefs = getSharedPreferences("GuardianShieldPrefs", MODE_PRIVATE);
        prefs.edit().putBoolean("isPauseAllActive", active).apply();

        runOnUiThread(() -> {
            try {
                Intent overlayIntent = new Intent(this, LockOverlayService.class);
                if (active) {
                    // Traz a Home nativa para frente (é o "lugar seguro" da criança agora)
                    Intent intent = new Intent(this, LauncherHomeActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);

                    // Solicita permissão de sobreposição caso ainda não tenha sido concedida
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                        permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(permissionIntent);
                    } else {
                        // Ativa a tela de sobreposição total (SYSTEM_ALERT_WINDOW)
                        overlayIntent.setAction("SHOW_OVERLAY");
                        overlayIntent.putExtra("title", "🔒 DISPOSITIVO BLOQUEADO");
                        overlayIntent.putExtra("message", "Pausa Geral Ativa ou Tempo Limite Esgotado.\nFale com seus pais para liberar o uso.");
                        startService(overlayIntent);
                    }
                } else {
                    // Remove a sobreposição quando desbloqueado
                    overlayIntent.setAction("HIDE_OVERLAY");
                    startService(overlayIntent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        SharedPreferences prefs = getSharedPreferences("GuardianShieldPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("isPauseAllActive", false)) {
            updateLockState(true);
        }
    }

    @Override
    public void onBackPressed() {
        SharedPreferences prefs = getSharedPreferences("GuardianShieldPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("isPauseAllActive", false)) {
            return;
        }
        if (this.bridge != null && this.bridge.getWebView() != null && this.bridge.getWebView().canGoBack()) {
            this.bridge.getWebView().goBack();
        } else {
            this.bridge.triggerJSEvent("backButton", "document");
        }
    }

    @CapacitorPlugin(name = "PauseModule")
    public static class PauseModule extends Plugin {
        @PluginMethod
        public void setPauseState(PluginCall call) {
            Boolean active = call.getBoolean("active", false);
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null) {
                activity.updateLockState(Boolean.TRUE.equals(active));
            }
            call.resolve();
        }

        @PluginMethod
        public void setBlockedApps(PluginCall call) {
            JSArray packagesArray = call.getArray("packages");
            Set<String> set = new HashSet<>();
            if (packagesArray != null) {
                try {
                    for (int i = 0; i < packagesArray.length(); i++) {
                        set.add(packagesArray.getString(i));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            SharedPreferences prefs = getContext().getSharedPreferences("GuardianShieldPrefs", Context.MODE_PRIVATE);
            prefs.edit().putStringSet("blockedPackagesSet", set).apply();
            call.resolve();
        }

        /**
         * Persiste o limite diário definido pelos pais (inclui bônus de tempo extra aprovado)
         * para a Home nativa (LauncherHomeActivity) e o AccessibilityService lerem. O
         * "usedMinutesToday" NÃO é mais gravado por aqui: quem conta de verdade é o
         * ParentalAccessibilityService, que fica vivo o tempo todo — diferente desta WebView,
         * que só existe enquanto a criança está com as Configurações abertas. Um parâmetro
         * usedMinutesToday recebido aqui (versões antigas do JS) é simplesmente ignorado.
         */
        @PluginMethod
        public void setScreenTimeInfo(PluginCall call) {
            Integer dailyLimitMinutes = call.getInt("dailyLimitMinutes", 120);
            SharedPreferences prefs = getContext().getSharedPreferences("GuardianShieldPrefs", Context.MODE_PRIVATE);
            prefs.edit()
                .putInt("dailyLimitMinutes", dailyLimitMinutes)
                .apply();
            call.resolve();
        }

        /**
         * Lê o tempo de uso contado de verdade nativamente, para esta Activity reportar ao
         * backend via telemetria (antes era um número aleatório de placeholder).
         */
        @PluginMethod
        public void getScreenTimeInfo(PluginCall call) {
            JSObject result = new JSObject();
            result.put("dailyLimitMinutes", GuardianPrefs.INSTANCE.dailyLimitMinutes(getContext()));
            result.put("usedMinutesToday", GuardianPrefs.INSTANCE.usedMinutesToday(getContext()));
            call.resolve(result);
        }

        @PluginMethod
        public void checkAccessibilityStatus(PluginCall call) {
            boolean enabled = MainActivity.isAccessibilityServiceEnabled(getContext());
            JSObject result = new JSObject();
            result.put("enabled", enabled);
            call.resolve(result);
        }

        @PluginMethod
        public void openAccessibilitySettings(PluginCall call) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        }

        /**
         * Permite ao React saber se este Activity foi aberto pela Home nativa pedindo
         * para abrir direto o modal de "Solicitar Tempo Extra".
         */
        @PluginMethod
        public void getLaunchIntentExtras(PluginCall call) {
            MainActivity activity = (MainActivity) getActivity();
            boolean openRequestModal = activity != null && activity.getIntent().getBooleanExtra("open_request_modal", false);
            if (activity != null && openRequestModal) {
                activity.getIntent().removeExtra("open_request_modal");
            }
            JSObject result = new JSObject();
            result.put("openRequestModal", openRequestModal);
            call.resolve(result);
        }
    }

    /**
     * Plugin de apoio à configuração da Home nativa: verifica se o GuardianShield já é a
     * tela inicial padrão do Android e abre a tela de configuração para o usuário trocar.
     * A listagem/abertura de apps em si agora é feita nativamente por LauncherHomeActivity
     * e LauncherDrawerActivity, sem depender do bridge Capacitor.
     */
    @CapacitorPlugin(name = "LauncherModule")
    public static class LauncherModule extends Plugin {

        @PluginMethod
        public void isDefaultLauncher(PluginCall call) {
            PackageManager pm = getContext().getPackageManager();
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolveInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            boolean isDefault = resolveInfo != null
                && resolveInfo.activityInfo != null
                && getContext().getPackageName().equals(resolveInfo.activityInfo.packageName);
            JSObject result = new JSObject();
            result.put("isDefault", isDefault);
            call.resolve(result);
        }

        @PluginMethod
        public void openHomeSettings(PluginCall call) {
            try {
                Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Exception e) {
                // Alguns fabricantes não expõem a tela de "app padrão de início";
                // como alternativa, dispara o seletor nativo de Launcher do próprio Android.
                Intent chooser = new Intent(Intent.ACTION_MAIN);
                chooser.addCategory(Intent.CATEGORY_HOME);
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(chooser);
            }
            call.resolve();
        }
    }

    /**
     * Baixa o APK de atualização via DownloadManager nativo — notificação de progresso
     * do próprio Android, sem abrir navegador nenhum — e abre a tela de instalação
     * sozinho ao concluir (ver updateDownloadReceiver/promptInstallDownloadedUpdate
     * em onCreate). Substitui o antigo window.open('_system'), que abria o navegador
     * como app separado e fazia o botão voltar sair do GuardianShield.
     */
    @CapacitorPlugin(name = "UpdaterModule")
    public static class UpdaterModule extends Plugin {
        @PluginMethod
        public void downloadAndInstall(PluginCall call) {
            String url = call.getString("url");
            String fileName = call.getString("fileName", "GuardianShield-atualizacao.apk");
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null || url == null) {
                call.reject("URL de download ausente.");
                return;
            }
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle("GuardianShield");
                request.setDescription("Baixando atualização...");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
                request.setMimeType("application/vnd.android.package-archive");
                DownloadManager downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                activity.pendingUpdateDownloadId = downloadManager.enqueue(request);
                call.resolve();
            } catch (Exception e) {
                call.reject("Falha ao iniciar o download da atualização.", e);
            }
        }
    }
}
