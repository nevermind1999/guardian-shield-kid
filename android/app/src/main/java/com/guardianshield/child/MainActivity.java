package com.guardianshield.child;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.guardianshield.child.services.LockOverlayService;
import com.guardianshield.child.services.ParentalAccessibilityService;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends BridgeActivity {
    public static boolean isPauseAllActive = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PauseModule.class);
        registerPlugin(LauncherModule.class);
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

    /**
     * Plugin que transforma o GuardianShield em uma tela inicial (Launcher) real:
     * lista os apps de verdade instalados no aparelho (com ícone), permite abrir
     * os liberados e nega o toque nos bloqueados diretamente na origem.
     */
    @CapacitorPlugin(name = "LauncherModule")
    public static class LauncherModule extends Plugin {

        @PluginMethod
        public void getInstalledApps(PluginCall call) {
            try {
                PackageManager pm = getContext().getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolvedApps = pm.queryIntentActivities(mainIntent, 0);
                String selfPackage = getContext().getPackageName();

                JSArray apps = new JSArray();
                for (ResolveInfo info : resolvedApps) {
                    String packageName = info.activityInfo.packageName;
                    if (selfPackage.equals(packageName)) continue; // Não lista o próprio GuardianShield

                    JSObject app = new JSObject();
                    app.put("package", packageName);
                    app.put("name", info.loadLabel(pm).toString());
                    app.put("icon", loadIconAsBase64(pm, info));
                    apps.put(app);
                }

                JSObject result = new JSObject();
                result.put("apps", apps);
                call.resolve(result);
            } catch (Exception e) {
                call.reject("Erro ao listar aplicativos instalados", e);
            }
        }

        @PluginMethod
        public void launchApp(PluginCall call) {
            String packageName = call.getString("package");
            if (packageName == null) {
                call.reject("Parâmetro 'package' é obrigatório");
                return;
            }
            PackageManager pm = getContext().getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(launchIntent);
                call.resolve();
            } else {
                call.reject("Não foi possível abrir o aplicativo: " + packageName);
            }
        }

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

        private String loadIconAsBase64(PackageManager pm, ResolveInfo info) {
            try {
                Drawable drawable = info.loadIcon(pm);
                Bitmap bitmap;
                if (drawable instanceof BitmapDrawable) {
                    bitmap = ((BitmapDrawable) drawable).getBitmap();
                } else {
                    int width = Math.max(drawable.getIntrinsicWidth(), 1);
                    int height = Math.max(drawable.getIntrinsicHeight(), 1);
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                    drawable.draw(canvas);
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos);
                return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
