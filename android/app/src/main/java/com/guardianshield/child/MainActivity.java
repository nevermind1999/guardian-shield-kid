package com.guardianshield.child;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends BridgeActivity {
    public static boolean isPauseAllActive = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PauseModule.class);
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

        // Persiste o estado no SharedPreferences para o AccessibilityService e o sistema lerem
        SharedPreferences prefs = getSharedPreferences("GuardianShieldPrefs", MODE_PRIVATE);
        prefs.edit().putBoolean("isPauseAllActive", active).apply();

        runOnUiThread(() -> {
            try {
                Intent overlayIntent = new Intent(this, LockOverlayService.class);
                if (active) {
                    // Traz o app para frente
                    Intent intent = new Intent(this, MainActivity.class);
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
    }
}
