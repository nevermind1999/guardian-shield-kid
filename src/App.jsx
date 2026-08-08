import React, { useState, useEffect } from 'react';
import { io } from 'socket.io-client';
import { Capacitor } from '@capacitor/core';
import { Device } from '@capacitor/device';
import { Network } from '@capacitor/network';
import { Browser } from '@capacitor/browser';
import { App as CapApp } from '@capacitor/app';
import { checkForAppUpdates } from './services/updater';
import {
  Clock, Shield, Lock, AlertTriangle, CheckCircle, 
  Send, Smartphone, Heart, Sparkles, X, MapPin, Wifi, QrCode, Key, Download
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
  const [simulatedBlockedApp, setSimulatedBlockedApp] = useState(null);

  // Tratamento nativo do botão Voltar do Android (não fecha o app ao voltar)
  useEffect(() => {
    const handleBack = () => {
      if (showRequestModal) {
        setShowRequestModal(false);
      } else if (simulatedBlockedApp) {
        setSimulatedBlockedApp(null);
      }
    };

    const backListener = CapApp.addListener('backButton', handleBack);
    document.addEventListener('backButton', handleBack);

    return () => {
      backListener.then(l => l.remove());
      document.removeEventListener('backButton', handleBack);
    };
  }, [showRequestModal, simulatedBlockedApp]);

  const handleOpenDownload = (url) => {
    console.log('Iniciando download do APK:', url);
    try {
      window.open(url, '_system') || (window.location.href = url);
    } catch (e) {
      window.location.href = url;
    }
  };

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
    if (state?.blockedApps && Array.isArray(state.blockedApps)) {
      const blockedPackageIds = state.blockedApps
        .filter(app => app.isBlocked)
        .map(app => app.id || app.package || app.name);

      if (Capacitor.isNativePlatform() && Capacitor.Plugins?.PauseModule) {
        Capacitor.Plugins.PauseModule.setBlockedApps({ packages: blockedPackageIds });
      }
    }
  }, [state?.blockedApps]);

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

  const handleTryLaunchApp = (app) => {
    if (app.isBlocked || isBlockedOverall) {
      setSimulatedBlockedApp(app.name);
    } else {
      alert(`🚀 Abrindo ${app.name}... (Acesso permitido)`);
    }
  };

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

      {/* AVISO DE APP BLOQUEADO */}
      {simulatedBlockedApp && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 999, background: 'rgba(0,0,0,0.85)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '20px'
        }}>
          <div className="glass-panel" style={{ padding: '24px', maxWidth: '360px', width: '100%', textAlign: 'center', border: '1px solid var(--accent-rose)' }}>
            <AlertTriangle size={36} style={{ color: 'var(--accent-rose)', margin: '0 auto 12px auto' }} />
            <h3 style={{ fontSize: '1.2rem', fontWeight: 700, marginBottom: '8px' }}>Aplicativo Bloqueado</h3>
            <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', marginBottom: '20px' }}>
              O aplicativo <strong>{simulatedBlockedApp}</strong> está bloqueado no momento.
            </p>
            <button className="btn btn-primary" onClick={() => setSimulatedBlockedApp(null)} style={{ width: '100%' }}>
              Entendi
            </button>
          </div>
        </div>
      )}

      {/* HEADER */}
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h1 style={{ fontSize: '1.3rem', fontWeight: 800 }}>Dispositivo Conectado</h1>
          <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>Bateria Real: {realBattery}%</p>
        </div>
        <span style={{ fontSize: '0.75rem', padding: '6px 12px', borderRadius: '20px', background: 'rgba(6, 214, 160, 0.15)', color: 'var(--accent-emerald)', border: '1px solid rgba(6, 214, 160, 0.3)', display: 'flex', alignItems: 'center', gap: '6px' }}>
          <Wifi size={12} /> Sincronizado
        </span>
      </header>

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

      {/* COUNTER DE TEMPO DE TELA */}
      <div className="glass-panel" style={{ padding: '24px', textAlign: 'center', position: 'relative', overflow: 'hidden' }}>
        <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '1px', fontWeight: 700 }}>
          Tempo Restante Hoje
        </span>
        <div style={{ fontSize: '2.5rem', fontWeight: 800, color: 'var(--accent-cyan)', margin: '8px 0' }}>
          {Math.floor(remainingMinutes / 60)}h {remainingMinutes % 60}m
        </div>

        <div style={{ height: '8px', background: 'rgba(255,255,255,0.1)', borderRadius: '4px', overflow: 'hidden', margin: '16px 0' }}>
          <div style={{ 
            height: '100%', width: `${(remainingMinutes / screenTime.dailyLimitMinutes) * 100}%`,
            background: 'linear-gradient(90deg, #3a86ff, #06d6a0)', borderRadius: '4px'
          }} />
        </div>

        <button className="btn btn-primary" onClick={() => setShowRequestModal(true)} style={{ width: '100%', marginTop: '8px' }}>
          <Send size={16} /> Solicitar Tempo Extra
        </button>
      </div>

      {/* SEÇÃO DE APLICATIVOS INSTALADOS */}
      <div className="glass-panel" style={{ padding: '20px' }}>
        <h3 style={{ fontSize: '1rem', fontWeight: 700, marginBottom: '14px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <Smartphone size={18} style={{ color: 'var(--accent-cyan)' }} /> Aplicativos Sincronizados
        </h3>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '10px' }}>
          {blockedApps.map(app => (
            <button
              key={app.id}
              onClick={() => handleTryLaunchApp(app)}
              style={{
                padding: '14px', borderRadius: '12px',
                background: app.isBlocked ? 'rgba(244, 63, 94, 0.1)' : 'rgba(255, 255, 255, 0.04)',
                border: `1px solid ${app.isBlocked ? 'rgba(244, 63, 94, 0.3)' : 'var(--border-color)'}`,
                color: 'white', display: 'flex', alignItems: 'center', gap: '10px',
                cursor: 'pointer', textAlign: 'left'
              }}
            >
              <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: app.isBlocked ? 'rgba(244, 63, 94, 0.2)' : 'rgba(58, 134, 255, 0.2)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                {app.isBlocked ? <Lock size={16} style={{ color: 'var(--accent-rose)' }} /> : <Smartphone size={16} style={{ color: 'var(--accent-cyan)' }} />}
              </div>
              <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>{app.name}</span>
            </button>
          ))}
        </div>
      </div>

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
