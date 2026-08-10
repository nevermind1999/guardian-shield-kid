import React, { useState, useEffect, useMemo } from 'react';
import { io } from 'socket.io-client';
import { Capacitor } from '@capacitor/core';
import { Device } from '@capacitor/device';
import { Network } from '@capacitor/network';
import { App as CapApp } from '@capacitor/app';
import { StatusBar, Style } from '@capacitor/status-bar';
import { checkForAppUpdates } from './services/updater';
import {
  Clock, Shield, Lock, AlertTriangle, CheckCircle,
  Send, Smartphone, X, QrCode, Key, Download, BatteryMedium, Moon, Sun
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
  // Preenchido de verdade assim que a primeira telemetria com a lista real do
  // aparelho (via LauncherModule.getInstalledApps) chegar no backend e voltar.
  blockedApps: [],
  rules: { dailyLimitMinutes: 120, isPauseAllActive: false, blockedApps: [] },
  location: { latitude: -23.550520, longitude: -46.633308 }
};

// Este app (Capacitor/React) é o painel de pareamento e configurações do GuardianShield.
// A tela inicial (Home) e a gaveta de apps do aparelho da criança são nativas em Kotlin
// (veja LauncherHomeActivity/LauncherDrawerActivity) — ficam responsivas e com cara de
// launcher de verdade, coisa que uma WebView nunca reproduz fielmente.
export default function App() {
  const [socket, setSocket] = useState(null);
  const [isConnected, setIsConnected] = useState(false);
  const [state, setState] = useState(DEFAULT_INITIAL_STATE);
  const [isPaired, setIsPaired] = useState(false);
  const [pairingCodeInput, setPairingCodeInput] = useState('');
  const [pairingError, setPairingError] = useState('');
  const [isPairingLoading, setIsPairingLoading] = useState(false);
  const [updateInfo, setUpdateInfo] = useState(null);
  const [showFullReleaseNotes, setShowFullReleaseNotes] = useState(false);
  const [showRequestModal, setShowRequestModal] = useState(false);
  // null = ainda não verificado / plataforma web; true/false = status real do Accessibility Service nativo
  const [accessibilityEnabled, setAccessibilityEnabled] = useState(null);
  // null = ainda não verificado; true/false = se o GuardianShield é a tela inicial padrão do Android
  const [isDefaultLauncher, setIsDefaultLauncher] = useState(null);
  // Lista real dos apps instalados no aparelho (via LauncherModule.getInstalledApps,
  // mesma fonte nativa que já alimenta a Home/Gaveta) — enviada na telemetria em vez
  // da lista fixa que existia antes.
  const [installedAppsList, setInstalledAppsList] = useState([]);

  // Modo claro/escuro: por padrão segue o tema do sistema (prefers-color-scheme);
  // se a pessoa já trocou manualmente antes, essa escolha prevalece.
  const [theme, setTheme] = useState(() => {
    const saved = localStorage.getItem('guardianshield_theme');
    if (saved) return saved;
    return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  });
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('guardianshield_theme', theme);
    if (Capacitor.isNativePlatform()) {
      StatusBar.setStyle({ style: theme === 'light' ? Style.Light : Style.Dark }).catch(() => {});
    }
  }, [theme]);
  const toggleTheme = () => setTheme(t => (t === 'light' ? 'dark' : 'light'));

  // Tratamento nativo do botão Voltar do Android (não fecha o app ao voltar)
  useEffect(() => {
    const handleBack = () => {
      if (showRequestModal) {
        setShowRequestModal(false);
      }
    };

    const backListener = CapApp.addListener('backButton', handleBack);
    document.addEventListener('backButton', handleBack);

    return () => {
      backListener.then(l => l.remove());
      document.removeEventListener('backButton', handleBack);
    };
  }, [showRequestModal]);

  // Verifica se o serviço de Acessibilidade (necessário para bloquear apps de fato) está habilitado
  // e se o GuardianShield já é a tela inicial padrão do Android. Ambos são passos manuais do usuário,
  // então revalidamos sempre que o app volta ao primeiro plano (ex: voltando das Configurações).
  const checkNativeStatus = () => {
    if (!Capacitor.isNativePlatform()) return;

    if (Capacitor.Plugins?.PauseModule?.checkAccessibilityStatus) {
      Capacitor.Plugins.PauseModule.checkAccessibilityStatus()
        .then(res => setAccessibilityEnabled(Boolean(res?.enabled)))
        .catch(() => setAccessibilityEnabled(null));
    }

    if (Capacitor.Plugins?.LauncherModule?.isDefaultLauncher) {
      Capacitor.Plugins.LauncherModule.isDefaultLauncher()
        .then(res => setIsDefaultLauncher(Boolean(res?.isDefault)))
        .catch(() => setIsDefaultLauncher(null));
    }
  };

  useEffect(() => {
    checkNativeStatus();

    // Se o app foi aberto pela Home nativa com o botão "Tempo extra", já abre o modal direto.
    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule?.getLaunchIntentExtras) {
      Capacitor.Plugins.PauseModule.getLaunchIntentExtras()
        .then(res => { if (res?.openRequestModal) setShowRequestModal(true); })
        .catch(() => {});
    }

    const resumeListener = CapApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) checkNativeStatus();
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
    // Baixa via DownloadManager nativo (notificação de progresso do próprio Android,
    // sem abrir navegador) e instala sozinho ao concluir — ver UpdaterModule em
    // MainActivity.java. Antes usava window.open('_system'), que abria o navegador
    // como app separado e fazia o botão voltar sair do GuardianShield.
    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.UpdaterModule?.downloadAndInstall) {
      Capacitor.Plugins.UpdaterModule.downloadAndInstall({ url, fileName: 'GuardianShield-Filho-atualizacao.apk' });
    } else {
      window.open(url, '_blank');
    }
  };

  // IDs (nomes de pacote Android) dos apps bloqueados pelos pais no momento — sincronizado
  // para o SharedPreferences nativo, de onde a Home/Gaveta em Kotlin e o Accessibility Service leem.
  const blockedPackageIdsSet = useMemo(() => {
    const ids = (state?.blockedApps || [])
      .filter(app => app.isBlocked)
      .map(app => app.id || app.package || app.name);
    return new Set(ids);
  }, [state?.blockedApps]);

  // Sincronização nativa do estado de Pausa Geral + limite diário definido pelos pais (a Home
  // nativa em Kotlin lê esses valores do SharedPreferences para se atualizar sozinha). O
  // "usado hoje" NÃO é mais empurrado daqui: quem conta de verdade é o
  // ParentalAccessibilityService nativo (fica vivo o tempo todo, diferente desta WebView) —
  // sobrescrever com o valor do servidor aqui reintroduziria o bug do contador não andar
  // sozinho, já que esta tela só existe enquanto a criança está nas Configurações.
  useEffect(() => {
    const dailyLimitMinutes = state?.screenTime?.dailyLimitMinutes || 120;
    const isPaused = state?.screenTime?.isPauseAllActive || false;

    if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule) {
      Capacitor.Plugins.PauseModule.setPauseState({ active: isPaused });
      Capacitor.Plugins.PauseModule.setScreenTimeInfo?.({ dailyLimitMinutes });
    }
  }, [state?.screenTime]);

  // Sincronização nativa da lista de Apps Bloqueados Individualmente (ex: TikTok, Free Fire)
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

  // 2b. Lista real de apps instalados (LauncherModule.getInstalledApps) — muda raramente,
  // então busca só ao abrir e depois a cada 5min, sem sobrecarregar a telemetria de 10s.
  useEffect(() => {
    if (!Capacitor.isNativePlatform() || !Capacitor.Plugins?.LauncherModule?.getInstalledApps) return;

    const fetchInstalledApps = async () => {
      try {
        const { apps } = await Capacitor.Plugins.LauncherModule.getInstalledApps();
        setInstalledAppsList(apps || []);
      } catch (e) {
        // mantém a última lista conhecida em caso de erro
      }
    };

    fetchInstalledApps();
    const interval = setInterval(fetchInstalledApps, 5 * 60 * 1000);
    return () => clearInterval(interval);
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

    const sendTelemetry = async () => {
      // Tempo usado hoje contado de verdade pelo ParentalAccessibilityService nativo (antes
      // era um número aleatório de placeholder aqui — por isso o contador nunca refletia o
      // uso real do aparelho).
      let usedMinutesToday = 0;
      if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule?.getScreenTimeInfo) {
        try {
          const info = await Capacitor.Plugins.PauseModule.getScreenTimeInfo();
          usedMinutesToday = info?.usedMinutesToday || 0;
        } catch (e) {
          // sem valor nativo disponível — mantém 0
        }
      }

      socket.emit('child:telemetry', {
        deviceId: deviceDetails.id,
        deviceName: deviceDetails.name,
        deviceModel: deviceDetails.model,
        batteryLevel: realBattery,
        networkType: networkInfo.connectionType || 'wifi',
        usedMinutesToday,
        location: realLocation,
        // Lista real do aparelho (ver efeito 2b acima) — antes era um array fixo de
        // 5 apps fake que nunca mudava, então o pai nunca via os apps de verdade.
        installedApps: installedAppsList.map(a => ({ package: a.packageName, name: a.label, category: a.category }))
      });
    };

    sendTelemetry();
    const interval = setInterval(sendTelemetry, 10000);
    return () => clearInterval(interval);
  }, [socket, isConnected, realBattery, realLocation, deviceDetails, networkInfo, installedAppsList]);

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
          <div style={{ width: '64px', height: '64px', borderRadius: '20px', background: 'linear-gradient(135deg, #3a86ff, #8338ec)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px auto', color: 'var(--text-on-accent)' }}>
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
                border: '1px solid var(--border-color)', background: 'var(--surface-2)',
                color: 'var(--text-primary)', outline: 'none', fontSize: '1.1rem', textAlign: 'center', fontWeight: 'bold', letterSpacing: '2px'
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

  // Notas de versão do commit do GitHub: mostra só a primeira linha (resumo) por padrão,
  // e esconde o resto (incluindo metadados tipo "Co-Authored-By") atrás de "Ver mais".
  const releaseNoteLines = (updateInfo?.releaseNotes || '')
    .split('\n')
    .map(l => l.trim())
    .filter(l => l && !l.startsWith('Co-Authored-By'));
  const releaseNoteSummary = releaseNoteLines[0] || 'Melhorias e correções.';
  const releaseNoteDetails = releaseNoteLines.slice(1).join('\n');

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

      {/* HEADER */}
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h1 style={{ fontSize: '1.3rem', fontWeight: 800 }}>Painel do GuardianShield</h1>
          <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <BatteryMedium size={14} /> {realBattery}%
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <button
            className="btn btn-ghost"
            onClick={toggleTheme}
            title={theme === 'light' ? 'Mudar para modo escuro' : 'Mudar para modo claro'}
            style={{ padding: '8px' }}
          >
            {theme === 'light' ? <Moon size={16} /> : <Sun size={16} />}
          </button>
          <span style={{ fontSize: '0.75rem', padding: '6px 12px', borderRadius: '20px', background: 'rgba(6, 214, 160, 0.15)', color: 'var(--accent-emerald)', border: '1px solid rgba(6, 214, 160, 0.3)' }}>
            Sincronizado
          </span>
        </div>
      </header>

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
          background: 'rgba(255, 190, 11, 0.12)', border: '1px solid var(--accent-amber)',
          display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '12px'
        }}>
          <div>
            <h4 style={{ fontSize: '0.95rem', fontWeight: 800, color: 'var(--accent-amber)', display: 'flex', alignItems: 'center', gap: '8px' }}>
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
            style={{ whiteSpace: 'nowrap', background: 'var(--accent-amber)', borderColor: 'var(--accent-amber)', color: '#0f172a' }}
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
          color: 'var(--text-on-accent)', display: 'flex', flexWrap: 'wrap', alignItems: 'center',
          justifyContent: 'space-between', gap: '12px', boxShadow: '0 8px 24px rgba(58, 134, 255, 0.3)'
        }}>
          <div>
            <h4 style={{ fontSize: '0.95rem', fontWeight: 800, display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Download size={18} /> Nova versão {updateInfo.latestVersion} disponível!
            </h4>
            <p style={{ fontSize: '0.8rem', opacity: 0.9, marginTop: '2px' }}>{releaseNoteSummary}</p>
            {releaseNoteDetails && (
              <>
                {showFullReleaseNotes && (
                  <p style={{ fontSize: '0.75rem', opacity: 0.85, marginTop: '6px', whiteSpace: 'pre-line' }}>
                    {releaseNoteDetails}
                  </p>
                )}
                <button
                  onClick={() => setShowFullReleaseNotes(v => !v)}
                  style={{ background: 'transparent', border: 'none', color: 'var(--text-on-accent)', textDecoration: 'underline', fontSize: '0.75rem', padding: 0, marginTop: '6px', cursor: 'pointer' }}
                >
                  {showFullReleaseNotes ? 'Ver menos' : 'Ver mais'}
                </button>
              </>
            )}
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
              style={{ background: 'transparent', border: 'none', color: 'var(--text-on-accent)', cursor: 'pointer', padding: '4px' }}
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

      {/* COUNTER DE TEMPO DE TELA */}
      <div className="glass-panel" style={{ padding: '24px', textAlign: 'center', position: 'relative', overflow: 'hidden' }}>
        <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '1px', fontWeight: 700 }}>
          Tempo Restante Hoje
        </span>
        <div style={{ fontSize: '2.5rem', fontWeight: 800, color: 'var(--accent-cyan)', margin: '8px 0' }}>
          {Math.floor(remainingMinutes / 60)}h {remainingMinutes % 60}m
        </div>

        <div style={{ height: '8px', background: 'var(--surface-3)', borderRadius: '4px', overflow: 'hidden', margin: '16px 0' }}>
          <div style={{
            height: '100%', width: `${(remainingMinutes / screenTime.dailyLimitMinutes) * 100}%`,
            background: 'linear-gradient(90deg, #3a86ff, #06d6a0)', borderRadius: '4px'
          }} />
        </div>

        <button className="btn btn-primary" onClick={() => setShowRequestModal(true)} style={{ width: '100%', marginTop: '8px' }}>
          <Send size={16} /> Solicitar Tempo Extra
        </button>
      </div>

      {/* MODAL DE PEDIDO DE MAIS TEMPO */}
      {showRequestModal && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 10000,
          background: 'var(--overlay-scrim)', backdropFilter: 'blur(10px)',
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
                  style={{ width: '100%', padding: '10px', borderRadius: '8px', background: 'var(--surface-2)', color: 'var(--text-primary)', border: '1px solid var(--border-color)', outline: 'none' }}
                >
                  <option value="15" style={{ background: 'var(--bg-card)' }}>15 minutos</option>
                  <option value="30" style={{ background: 'var(--bg-card)' }}>30 minutos</option>
                  <option value="60" style={{ background: 'var(--bg-card)' }}>1 hora</option>
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
                  style={{ width: '100%', padding: '10px', borderRadius: '8px', background: 'var(--surface-2)', color: 'var(--text-primary)', border: '1px solid var(--border-color)', outline: 'none' }}
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
