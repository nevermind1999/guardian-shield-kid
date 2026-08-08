package com.guardianshield.child;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

public class MainActivity extends BridgeActivity {
    public static boolean isPauseAllActive = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PauseModule.class);
        super.onCreate(savedInstanceState);
        
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

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Se a Pausa Geral / Bloqueio Total estiver ativo, força o app a permanecer em primeiro plano ao tentar sair (Home / Trocar de app)
        if (isPauseAllActive) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }
    }

    @Override
    public void onBackPressed() {
        if (isPauseAllActive) {
            // Se bloqueado totalmente pelos pais, impede que o botão Voltar saia da tela
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
            isPauseAllActive = Boolean.TRUE.equals(active);
            if (isPauseAllActive && getActivity() != null) {
                Intent intent = new Intent(getActivity(), MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                getActivity().startActivity(intent);
            }
            call.resolve();
        }
    }
}
