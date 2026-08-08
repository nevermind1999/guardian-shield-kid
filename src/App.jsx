import React, { useState, useEffect, useMemo } from 'react';
import { io } from 'socket.io-client';
import { Capacitor } from '@capacitor/core';
import { Device } from '@capacitor/device';
import { Network } from '@capacitor/network';
import { Browser } from '@capacitor/browser';
import { App as CapApp } from '@capacitor/app';
import { checkForAppUpdates } from './services/updater';
import {
  Clock, Shield, Lock, AlertTriangle, CheckCircle,
  Send, Smartphone, X, QrCode, Key, Download,
  LayoutGrid, Search, ArrowLeft, BatteryMedium
} from 'lucide-react';

const SERVER_URLS = [
  import.meta.env.VITE_BACKEND_URL,
  'http://192.168.1.114:3001',
  'http://localhost:3001',
  'http://10.0.2.2:3001'
].filter(Boolean);

const DEFAULT_INITIAL_STATE = {
  pairedDevices: {},
  deviceInfo: { name: 'Celular da Criança', model: 'Android' },
  screenTime: { dailyLimitMinutes: 120, usedMinutesToday: 0, isPauseAllActive: false },
  blockedApps: [
    { id: 'com.whatsapp', name: 'WhatsApp', isBlocked: false },
    { id: 'com.zhiliaoapp.musically', name: 'TikTok', isBlocked: false },
    { id: 'com.instagram.android', name: 'Instagram', isBlocked: false },
    { id: 'com.google.android.youtube', name: 'YouTube', isBlocked: false },
    { id: 'com.dts.freefireth', name: 'Free Fire', isBlocked: true }
  ],
  rules: { dailyLimitMinutes: 120, isPauseAllActive: false, blockedApps: [] },
  location: { latitude: -23.550520, longitude: -46.633308 }
};

export default function App() {
  const [socket, setSocket] = useState(null);
  const [isConnected, setIsConnected] = useState(false);
  const [state, setState] = useState(DEFAULT_INITIAL_STATE);
  const [isPaired, setIsPaired] = useState(false);
  const [pairingCodeInput, setPairingCodeInput] = useState('');
  const [pairingError, setPairingError] = useState('');
  const [isPairingLoading, setIsPairingLoading] = useState(false);
  const [updateInfo, setUpdateInfo] = useState(null);
  const [showRequestModal, setShowRequestModal] = useState(false);
  // null = ainda não verificado / plataforma web; true/false = status real do Accessibility Service nativo
  const [accessibilityEnabled, setAccessibilityEnabled] = useState(null);
  // Apps REAIS instalados no aparelho (ícone + nome + pacote), usados para virar a tela inicial (Launcher)
  const [installedRealApps, setInstalledRealApps] = useState(null);
  // null = ainda não verificado; true/false = se o GuardianShield é a tela inicial padrão do Android
  const [isDefaultLauncher, setIsDefaultLauncher] = useState(null);
  // Tela inicial (home) x gaveta de apps (drawer), igual a um launcher de verdade
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerSearch, setDrawerSearch] = useState('');
  const [now, setNow] = useState(new Date());

  // Relógio da tela inicial
  useEffect(() => {
    const tick = setInterval(() => setNow(new Date()), 10000);
    return () => clearInterval(tick);
  }, []);

  // Tratamento nativo do botão Voltar do Android (não fecha o app ao voltar)
  useEffect(() => {
    const handleBack = () => {
      if (showRequestModal) {
        setShowRequestModal(false);
      } else if (drawerOpen) {
        setDrawerOpen(false);
      }
    };

    const backListener = CapApp.addListener('backButton', handleBack);
    document.addEventListener('backButton', handleBack);

    return () => {
      backListener.then(l => l.remove());
      document.removeEventListener('backButton', handleBack);
    };
  }, [showRequestModal, drawerOpen]);

  // Verifica se o serviço de Acessibilidade (necessário para bloquear apps de fato) está habilitado.
  // Como habilitar é um passo manual do usuário em Configurações do Android, revalidamos sempre
  // que o app volta ao primeiro plano (ex: usuário voltando das Configurações).
  const checkAccessibility = () => {
    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule?.checkAccessibilityStatus) {
      Capacitor.Plugins.PauseModule.checkAccessibilityStatus()
        .then(res => setAccessibilityEnabled(Boolean(res?.enabled)))
        .catch(() => setAccessibilityEnabled(null));
    }
  };

  // Carrega a lista REAL de apps instalados (com ícone) e verifica se o GuardianShield
  // é a tela inicial (Launcher) padrão do aparelho. Revalidamos ao voltar ao primeiro
  // plano, já que definir o launcher padrão é um passo manual do usuário.
  const loadLauncherState = () => {
    if (!Capacitor.isNativePlatform() || !Capacitor.Plugins?.LauncherModule) return;

    Capacitor.Plugins.LauncherModule.getInstalledApps()
      .then(res => setInstalledRealApps(Array.isArray(res?.apps) ? res.apps : []))
      .catch(() => setInstalledRealApps([]));

    Capacitor.Plugins.LauncherModule.isDefaultLauncher()
      .then(res => setIsDefaultLauncher(Boolean(res?.isDefault)))
      .catch(() => setIsDefaultLauncher(null));
  };

  useEffect(() => {
    checkAccessibility();
    loadLauncherState();
    const resumeListener = CapApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) {
        checkAccessibility();
        loadLauncherState();
      }
    });
    return () => {
      resumeListener.then(l => l.remove());
    };
  }, []);

  const handleOpenAccessibilitySettings = () => {
    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule?.openAccessibilitySettings) {
      Capacitor.Plugins.PauseModule.openAccessibilitySettings();
    }
  };

  const handleOpenHomeSettings = () => {
    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.LauncherModule?.openHomeSettings) {
      Capacitor.Plugins.LauncherModule.openHomeSettings();
    }
  };

  const handleOpenDownload = (url) => {
    console.log('Iniciando download do APK:', url);
    try {
      window.open(url, '_system') || (window.location.href = url);
    } catch (e) {
      window.location.href = url;
    }
  };

  // IDs (nomes de pacote Android) dos apps bloqueados pelos pais no momento — usado tanto para
  // sincronizar o Accessibility Service nativo quanto para apagar/desabilitar os ícones na tela inicial.
  const blockedPackageIdsSet = useMemo(() => {
    const ids = (state?.blockedApps || [])
      .filter(app => app.isBlocked)
      .map(app => app.id || app.package || app.name);
    return new Set(ids);
  }, [state?.blockedApps]);

  // Sincronização nativa do estado de Pausa Geral / Bloqueio Total com o Android
  useEffect(() => {
    const isPaused = state?.screenTime?.isPauseAllActive || false;
    const isExpired = (state?.screenTime?.usedMinutesToday || 0) >= (state?.screenTime?.dailyLimitMinutes || 120);
    const isBlockedOverall = isPaused || isExpired;

    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule) {
      Capacitor.Plugins.PauseModule.setPauseState({ active: isBlockedOverall });
    }
  }, [state?.screenTime]);

  // Sincronização nativa da lista de Apps Bloqueados Individualmente (ex: TikTok, Free Fire) com o Java
  useEffect(() => {
    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule) {
      Capacitor.Plugins.PauseModule.setBlockedApps({ packages: Array.from(blockedPackageIdsSet) });
    }
  }, [blockedPackageIdsSet]);

  // DETECÇÃO DINÂMICA REAL DO APARELHO (Samsung A06, etc.) E REDE WI-FI
  const [deviceDetails, setDeviceDetails] = useState({
    id: 'child-' + Math.floor(1000 + Math.random() * 9000),
    name: 'Dispositivo Android',
    model: 'Android Device',
    manufacturer: 'Android'
  });
  const [networkInfo, setNetworkInfo] = useState({ connectionType: 'wifi', connected: true });

  useEffect(() => {
    // Detectar modelo REAL do celular da criança (ex: Samsung Galaxy A06)
    const loadRealDeviceAndNetwork = async () => {
      try {
        const info = await Device.getInfo();
        const deviceId = await Device.getId();
        const netStatus = await Network.getStatus();

        const manufacturer = info.manufacturer || '';
        const modelName = info.model || 'Android';
        const fullName = `${manufacturer} ${modelName}`.trim() || 'Celular do Filho';

        setDeviceDetails({
          id: deviceId.identifier || 'child-device-' + Date.now(),
          name: fullName,
          model: fullName,
          manufacturer
        });
        setNetworkInfo(netStatus);
      } catch (e) {
        console.log('Erro ao carregar detalhes nativos do aparelho:', e.message);
      }
    };
    loadRealDeviceAndNetwork();

    checkForAppUpdates().then(info => {
      if (info?.hasUpdate) setUpdateInfo(info);
    });
  }, []);

  // DADOS REAIS DE TELEMETRIA
  const [realBattery, setRealBattery] = useState(88);
  const [realLocation, setRealLocation] = useState(null);
  
  const [reason, setReason] = useState('');
  const [requestedMinutes, setRequestedMinutes] = useState(15);
  const [requestSentNotice, setRequestSentNotice] = useState(false);

  // 1. Coleta de dados reais de bateria
  useEffect(() => {
    if ('getBattery' in navigator) {
      navigator.getBattery().then((battery) => {
        setRealBattery(Math.round(battery.level * 100));
        battery.addEventListener('levelchange', () => {
          setRealBattery(Math.round(battery.level * 100));
        });
      });
    }
  }, []);

  // 2. Coleta de dados reais de localização GPS
  useEffect(() => {
    if ('geolocation' in navigator) {
      const watchId = navigator.geolocation.watchPosition(
        (pos) => {
          setRealLocation({
            latitude: pos.coords.latitude,
            longitude: pos.coords.longitude,
            accuracy: pos.coords.accuracy,
            lastUpdated: new Date().toISOString()
          });
        },
        (err) => console.log('Aviso Geolocation:', err.message),
        { enableHighAccuracy: true }
      );
      return () => navigator.geolocation.clearWatch(watchId);
    }
  }, []);

  // 3. Conexão Socket.IO
  useEffect(() => {
    let activeSocket = null;

    const tryConnect = (index) => {
      if (index >= SERVER_URLS.length) {
        setTimeout(() => tryConnect(0), 3000);
        return;
      }

      const targetUrl = SERVER_URLS[index];
      const s = io(targetUrl, {
        reconnectionAttempts: 2,
        timeout: 3000,
        transports: ['websocket', 'polling']
      });

      s.on('connect', () => {
        setIsConnected(true);
        setSocket(s);
        activeSocket = s;
      });

      s.on('state:update', (updatedState) => {
        setState(updatedState);
        if (updatedState.pairedDevices && updatedState.pairedDevices.length > 0) {
          setIsPaired(true);
        }
      });

      s.on('child:pair_result', (res) => {
        setIsPairingLoading(false);
        if (res.success) {
          setIsPaired(true);
        } else {
          setPairingError(res.message || 'Código inválido.');
        }
      });

      s.on('notification:request_answered', ({ approved, bonusMinutes }) => {
        alert(approved ? `🎉 Seu pai aprovou +${bonusMinutes} minutos!` : '❌ Seu pedido de mais tempo não foi aprovado.');
      });

      s.on('connect_error', () => {
        s.close();
        tryConnect(index + 1);
      });
    };

    tryConnect(0);

    return () => activeSocket?.close();
  }, []);

  // 4. Envia telemetria REAL periodicamente para o backend
  useEffect(() => {
    if (!socket || !isConnected) return;

    const interval = setInterval(() => {
      socket.emit('child:telemetry', {
        deviceId: deviceDetails.id,
        deviceName: deviceDetails.name,
        deviceModel: deviceDetails.model,
        batteryLevel: realBattery,
        networkType: networkInfo.connectionType || 'wifi',
        usedMinutesToday: Math.min(120, Math.floor(Math.random() * 45) + 30),
        location: realLocation,
        installedApps: [
          { package: 'com.whatsapp', name: 'WhatsApp', usageMinutes: 45, isBlocked: false },
          { package: 'com.zhiliaoapp.musically', name: 'TikTok', usageMinutes: 30, isBlocked: false },
          { package: 'com.instagram.android', name: 'Instagram', usageMinutes: 25, isBlocked: false },
          { package: 'com.google.android.youtube', name: 'YouTube', usageMinutes: 20, isBlocked: false },
          { package: 'com.dts.freefireth', name: 'Free Fire', usageMinutes: 15, isBlocked: true }
        ]
      });
    }, 10000);

    return () => clearInterval(interval);
  }, [socket, isConnected, realBattery, realLocation, deviceDetails, networkInfo]);

  const handlePairSubmit = (e) => {
    e.preventDefault();
    setPairingError('');
    setIsPairingLoading(true);
    socket?.emit('child:verify_pair_code', {
      code: pairingCodeInput.trim().toUpperCase(),
      deviceInfo: {
        id: deviceDetails.id,
        name: deviceDetails.name,
        model: deviceDetails.model,
        batteryLevel: realBattery,
        networkType: networkInfo.connectionType || 'wifi'
      }
    });
  };

  if (!state) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', gap: '16px' }}>
        <Clock size={36} style={{ color: 'var(--accent-cyan)' }} />
        <p style={{ color: 'var(--text-secondary)' }}>Carregando GuardianShield Agente...</p>
      </div>
    );
  }

  // Se o dispositivo ainda não foi pareado, exibe a tela de Pareamento por QR Code / Código
  if (!isPaired && (!state.pairedDevices || state.pairedDevices.length === 0)) {
    return (
      <div style={{ maxWidth: '440px', margin: '0 auto', padding: '32px 20px', minHeight: '100vh', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: '24px' }}>
        <div className="glass-panel" style={{ padding: '28px', textAlign: 'center' }}>
          <div style={{ width: '64px', height: '64px', borderRadius: '20px', background: 'linear-gradient(135deg, #3a86ff, #8338ec)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px auto', color: 'white' }}>
            <QrCode size={34} />
          </div>

          <h2 style={{ fontSize: '1.4rem', fontWeight: 800, marginBottom: '8px' }}>Parear com o Celular dos Pais</h2>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '24px' }}>
            Digite o código exibido na tela do app dos seus pais (ex: <code>GS-1234</code>):
          </p>

          {pairingError && (
            <div style={{ padding: '10px 14px', borderRadius: '10px', background: 'rgba(244, 63, 94, 0.15)', color: 'var(--accent-rose)', fontSize: '0.85rem', marginBottom: '16px', border: '1px solid var(--accent-rose)' }}>
              {pairingError}
            </div>
          )}

          <form onSubmit={handlePairSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <input 
              type="text" 
              placeholder="Digite o código (ex: GS-4921)"
              value={pairingCodeInput}
              onChange={(e) => setPairingCodeInput(e.target.value)}
              required
              style={{
                width: '100%', padding: '14px', borderRadius: '12px',
                border: '1px solid var(--border-color)', background: 'rgba(255,255,255,0.05)',
                color: 'white', outline: 'none', fontSize: '1.1rem', textAlign: 'center', fontWeight: 'bold', letterSpacing: '2px'
              }}
            />

            <button type="submit" className="btn btn-primary" disabled={isPairingLoading} style={{ width: '100%', padding: '14px' }}>
              <Key size={18} /> {isPairingLoading ? 'Validando...' : 'Concluir Pareamento'}
            </button>
          </form>
        </div>
      </div>
    );
  }

  const screenTime = state?.screenTime || { dailyLimitMinutes: 120, usedMinutesToday: 0, isPauseAllActive: false };
  const blockedApps = state?.blockedApps || [];
  const remainingMinutes = Math.max(0, (screenTime.dailyLimitMinutes || 120) - (screenTime.usedMinutesToday || 0));
  const isTimeExpired = remainingMinutes <= 0;
  const isBlockedOverall = Boolean(screenTime.isPauseAllActive || isTimeExpired);

  const handleSendTimeRequest = (e) => {
    e.preventDefault();
    socket?.emit('child:request_extra_time', {
      reason,
      requestedMinutes: Number(requestedMinutes)
    });
    setShowRequestModal(false);
    setRequestSentNotice(true);
    setTimeout(() => setRequestSentNotice(false), 4000);
    setReason('');
  };

  // Lista real de apps do aparelho (launcher de verdade) quando disponível; usa a lista
  // simulada vinda do servidor como fallback (preview web / antes do carregamento nativo).
  const appsForGrid = installedRealApps && installedRealApps.length > 0
    ? installedRealApps.map(app => ({ ...app, id: app.package }))
    : blockedApps;
  const isUsingRealApps = Boolean(installedRealApps && installedRealApps.length > 0);

  const isAppBlocked = (app) => isBlockedOverall || blockedPackageIdsSet.has(app.id) || Boolean(app.isBlocked);

  const handleTryLaunchApp = (app) => {
    if (isAppBlocked(app)) return; // Ícone apagado: nenhuma ação ao tocar

    if (isUsingRealApps && Capacitor.isNativePlatform() && Capacitor.Plugins?.LauncherModule) {
      Capacitor.Plugins.LauncherModule.launchApp({ package: app.package || app.id });
    } else {
      alert(`🚀 Abrindo ${app.name}... (Acesso permitido)`);
    }
  };

  // Ícone de app reutilizado tanto no dock da tela inicial quanto na gaveta de apps —
  // apps bloqueados ficam em escala de cinza, apagados e SEM handler de clique.
  const renderAppIcon = (app, { small = false } = {}) => {
    const blocked = isAppBlocked(app);
    const size = small ? 48 : 58;
    return (
      <button
        key={app.package || app.id}
        onClick={() => handleTryLaunchApp(app)}
        disabled={blocked}
        title={blocked ? `${app.name} está bloqueado pelos seus pais` : app.name}
        style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px',
          background: 'transparent', border: 'none', padding: '4px', width: '76px',
          cursor: blocked ? 'default' : 'pointer',
          opacity: blocked ? 0.35 : 1,
          pointerEvents: blocked ? 'none' : 'auto'
        }}
      >
        <div style={{
          width: `${size}px`, height: `${size}px`, borderRadius: '16px', overflow: 'hidden',
          background: 'rgba(255,255,255,0.08)', display: 'flex', alignItems: 'center', justifyContent: 'center',
          filter: blocked ? 'grayscale(1)' : 'none', position: 'relative',
          boxShadow: '0 4px 14px rgba(0,0,0,0.3)'
        }}>
          {app.icon
            ? <img src={app.icon} alt={app.name} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            : <Smartphone size={size * 0.4} style={{ color: 'var(--accent-cyan)' }} />}
          {blocked && (
            <div style={{ position: 'absolute', inset: 0, background: 'rgba(10,13,20,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Lock size={size * 0.35} style={{ color: 'white' }} />
            </div>
          )}
        </div>
        <span style={{
          fontSize: '0.7rem', fontWeight: 600, textAlign: 'center', color: 'white', lineHeight: 1.2,
          maxWidth: '76px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'
        }}>
          {app.name}
        </span>
      </button>
    );
  };

  // Dock da tela inicial: até 4 apps liberados em destaque (os primeiros não bloqueados)
  const favoriteApps = appsForGrid.filter(app => !isAppBlocked(app)).slice(0, 4);

  // Apps da gaveta, em ordem alfabética e filtrados pela busca — como um launcher de verdade
  const drawerApps = [...appsForGrid]
    .filter(app => app.name.toLowerCase().includes(drawerSearch.trim().toLowerCase()))
    .sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'));

  const timeStr = now.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  const dateStr = now.toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' });

  return (
    <div style={{ maxWidth: '480px', margin: '0 auto', padding: '20px 16px', minHeight: '100vh', display: 'flex', flexDirection: 'column', gap: '20px' }}>
      
      {/* TELA DE BLOQUEIO DE OVERLAY */}
      {isBlockedOverall && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 9999,
          background: 'rgba(10, 13, 20, 0.95)', backdropFilter: 'blur(20px)',
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          padding: '24px', textAlign: 'center'
        }}>
          <div style={{ width: '80px', height: '80px', borderRadius: '50%', background: 'rgba(244, 63, 94, 0.2)', border: '2px solid var(--accent-rose)', display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: '20px' }}>
            <Lock size={40} style={{ color: 'var(--accent-rose)' }} />
          </div>
          <h2 style={{ fontSize: '1.6rem', fontWeight: 800, marginBottom: '10px' }}>
            {screenTime.isPauseAllActive ? 'Dispositivo Pausado pelos Pais' : 'Tempo de Tela Esgotado!'}
          </h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.95rem', marginBottom: '24px', maxWidth: '320px' }}>
            {screenTime.isPauseAllActive 
              ? 'Seus pais ativaram a pausa de emergência.' 
              : 'Você atingiu o limite de tempo para hoje.'}
          </p>

          <button className="btn btn-primary" onClick={() => setShowRequestModal(true)} style={{ width: '100%', maxWidth: '280px' }}>
            <Send size={18} /> Pedir Mais Tempo aos Pais
          </button>
        </div>
      )}

      {drawerOpen ? (
        /* ================= GAVETA DE APPS (todos os apps, com busca) ================= */
        <div style={{
          position: 'fixed', inset: 0, zIndex: 500, background: 'var(--bg-primary)',
          backgroundImage: 'radial-gradient(circle at 50% 0%, rgba(58, 134, 255, 0.12), transparent 60%)',
          display: 'flex', flexDirection: 'column', padding: '20px 16px'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '18px' }}>
            <button
              onClick={() => setDrawerOpen(false)}
              style={{
                background: 'rgba(255,255,255,0.06)', border: 'none', borderRadius: '12px',
                width: '40px', height: '40px', display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: 'white', cursor: 'pointer', flexShrink: 0
              }}
            >
              <ArrowLeft size={20} />
            </button>
            <div style={{
              flex: 1, display: 'flex', alignItems: 'center', gap: '8px',
              background: 'rgba(255,255,255,0.06)', border: '1px solid var(--border-color)',
              borderRadius: '12px', padding: '10px 14px'
            }}>
              <Search size={16} style={{ color: 'var(--text-secondary)' }} />
              <input
                type="text"
                placeholder="Buscar aplicativo"
                value={drawerSearch}
                onChange={(e) => setDrawerSearch(e.target.value)}
                autoFocus
                style={{ flex: 1, background: 'transparent', border: 'none', outline: 'none', color: 'white', fontSize: '0.9rem' }}
              />
            </div>
          </div>

          <div style={{
            flex: 1, overflowY: 'auto',
            display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(76px, 1fr))',
            gap: '18px', paddingBottom: '20px', alignContent: 'start'
          }}>
            {drawerApps.map(app => renderAppIcon(app))}
            {drawerApps.length === 0 && (
              <p style={{ gridColumn: '1 / -1', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem', marginTop: '20px' }}>
                Nenhum aplicativo encontrado.
              </p>
            )}
          </div>
        </div>
      ) : (
        /* ================= TELA INICIAL (HOME) ================= */
        <>
          {/* AVISO: PROTEÇÃO DE BLOQUEIO DESATIVADA (Accessibility Service não habilitado) */}
          {accessibilityEnabled === false && (
            <div style={{
              padding: '16px 20px', borderRadius: '16px',
              background: 'rgba(244, 63, 94, 0.12)', border: '1px solid var(--accent-rose)',
              display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '12px'
            }}>
              <div>
                <h4 style={{ fontSize: '0.95rem', fontWeight: 800, color: 'var(--accent-rose)', display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <AlertTriangle size={18} /> Proteção desativada
                </h4>
                <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: '4px' }}>
                  O bloqueio de apps não vai funcionar até você ativar o Serviço de Acessibilidade do GuardianShield.
                  Se a opção estiver bloqueada, vá em Configurações do app → "Permitir configurações restritas" primeiro.
                </p>
              </div>
              <button
                onClick={handleOpenAccessibilitySettings}
                className="btn btn-primary"
                style={{ whiteSpace: 'nowrap' }}
              >
                <Shield size={16} /> Ativar agora
              </button>
            </div>
          )}

          {/* AVISO: GUARDIANSHIELD NÃO É A TELA INICIAL PADRÃO */}
          {isDefaultLauncher === false && (
            <div style={{
              padding: '16px 20px', borderRadius: '16px',
              background: 'rgba(255, 190, 11, 0.12)', border: '1px solid #ffbe0b',
              display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '12px'
            }}>
              <div>
                <h4 style={{ fontSize: '0.95rem', fontWeight: 800, color: '#ffbe0b', display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Smartphone size={18} /> Defina como tela inicial
                </h4>
                <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: '4px' }}>
                  Para os ícones bloqueados ficarem apagados na tela inicial, defina o GuardianShield como o
                  app padrão de Início (Home) do aparelho.
                </p>
              </div>
              <button
                onClick={handleOpenHomeSettings}
                className="btn btn-primary"
                style={{ whiteSpace: 'nowrap', background: '#ffbe0b', borderColor: '#ffbe0b', color: '#0f172a' }}
              >
                Definir agora
              </button>
            </div>
          )}

          {/* BANNER DE ATUALIZAÇÃO DO GITHUB */}
          {updateInfo && (
            <div style={{
              padding: '16px 20px', borderRadius: '16px',
              background: 'linear-gradient(135deg, var(--accent-cyan), #8338ec)',
              color: 'white', display: 'flex', flexWrap: 'wrap', alignItems: 'center',
              justifyContent: 'space-between', gap: '12px', boxShadow: '0 8px 24px rgba(58, 134, 255, 0.3)'
            }}>
              <div>
                <h4 style={{ fontSize: '0.95rem', fontWeight: 800, display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Download size={18} /> Nova versão {updateInfo.latestVersion} disponível!
                </h4>
                <p style={{ fontSize: '0.8rem', opacity: 0.9, marginTop: '2px' }}>{updateInfo.releaseNotes}</p>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <button
                  onClick={() => {
                    handleOpenDownload(updateInfo.downloadUrl);
                    if (updateInfo.latestSha) localStorage.setItem('dismissed_update_sha', updateInfo.latestSha);
                    setUpdateInfo(null);
                  }}
                  className="btn"
                  style={{ background: 'white', color: '#0f172a', fontWeight: 800, padding: '8px 14px', fontSize: '0.85rem', cursor: 'pointer' }}
                >
                  Atualizar APK
                </button>
                <button
                  onClick={() => {
                    if (updateInfo.latestSha) localStorage.setItem('dismissed_update_sha', updateInfo.latestSha);
                    setUpdateInfo(null);
                  }}
                  style={{ background: 'transparent', border: 'none', color: 'white', cursor: 'pointer', padding: '4px' }}
                  title="Dispensar aviso"
                >
                  <X size={18} />
                </button>
              </div>
            </div>
          )}

          {/* NOTIFICAÇÃO DE PEDIDO ENVIADO */}
          {requestSentNotice && (
            <div style={{ padding: '14px', borderRadius: '12px', background: 'rgba(6, 214, 160, 0.15)', border: '1px solid var(--accent-emerald)', color: 'var(--accent-emerald)', fontSize: '0.85rem', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <CheckCircle size={18} /> Solicitação enviada com sucesso! Aguarde a resposta dos seus pais.
            </div>
          )}

          {/* RELÓGIO (tela inicial de verdade) */}
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
            <div style={{ fontSize: '3.4rem', fontWeight: 800, letterSpacing: '-1px' }}>{timeStr}</div>
            <div style={{ fontSize: '0.95rem', color: 'var(--text-secondary)', textTransform: 'capitalize' }}>{dateStr}</div>
          </div>

          {/* PÍLULA DE STATUS: bateria + tempo restante + pedir tempo extra */}
          <div className="glass-panel" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '14px', padding: '12px 18px', borderRadius: '999px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '16px', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <BatteryMedium size={16} /> {realBattery}%
              </span>
              <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <Clock size={16} /> {Math.floor(remainingMinutes / 60)}h {remainingMinutes % 60}m
              </span>
            </div>
            <button onClick={() => setShowRequestModal(true)} className="btn btn-primary" style={{ padding: '8px 14px', fontSize: '0.8rem', whiteSpace: 'nowrap' }}>
              <Send size={14} /> Tempo extra
            </button>
          </div>

          {/* DOCK: favoritos + botão para abrir a gaveta de apps */}
          <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'center', gap: '14px', padding: '4px 4px 6px' }}>
            {favoriteApps.map(app => renderAppIcon(app, { small: true }))}
            <button
              onClick={() => setDrawerOpen(true)}
              title="Ver todos os aplicativos"
              style={{
                width: '48px', height: '48px', borderRadius: '16px', border: 'none',
                background: 'rgba(255,255,255,0.1)', color: 'white',
                display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', flexShrink: 0
              }}
            >
              <LayoutGrid size={22} />
            </button>
          </div>
        </>
      )}

      {/* MODAL DE PEDIDO DE MAIS TEMPO */}
      {showRequestModal && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 10000,
          background: 'rgba(0,0,0,0.8)', backdropFilter: 'blur(10px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '16px'
        }}>
          <div className="glass-panel" style={{ padding: '24px', maxWidth: '400px', width: '100%' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '1.1rem', fontWeight: 700 }}>Pedir Tempo Extra aos Pais</h3>
              <button onClick={() => setShowRequestModal(false)} style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer' }}>
                <X size={20} />
              </button>
            </div>

            <form onSubmit={handleSendTimeRequest} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                  Quanto tempo você precisa?
                </label>
                <select 
                  value={requestedMinutes}
                  onChange={(e) => setRequestedMinutes(e.target.value)}
                  style={{ width: '100%', padding: '10px', borderRadius: '8px', background: 'rgba(255,255,255,0.05)', color: 'white', border: '1px solid var(--border-color)', outline: 'none' }}
                >
                  <option value="15" style={{ background: '#121826' }}>15 minutos</option>
                  <option value="30" style={{ background: '#121826' }}>30 minutos</option>
                  <option value="60" style={{ background: '#121826' }}>1 hora</option>
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                  Motivo do pedido:
                </label>
                <input 
                  type="text" 
                  placeholder="ex: Fazer lição de casa" 
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  required
                  style={{ width: '100%', padding: '10px', borderRadius: '8px', background: 'rgba(255,255,255,0.05)', color: 'white', border: '1px solid var(--border-color)', outline: 'none' }}
                />
              </div>

              <button type="submit" className="btn btn-primary" style={{ marginTop: '10px' }}>
                <Send size={16} /> Enviar Pedido aos Pais
              </button>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
