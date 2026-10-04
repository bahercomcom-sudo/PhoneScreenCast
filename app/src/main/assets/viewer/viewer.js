(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const video = $('screen');
  const statusEl = $('status');
  const statusText = $('statusText');
  const overlay = $('overlay');
  const overlayText = $('overlayText');
  const playBtn = $('playBtn');
  const fsBtn = $('fsBtn');

  const LABELS = { connecting: 'Connecting...', connected: 'Connected', disconnected: 'Disconnected' };
  const RECONNECT_MIN_MS = 1000;
  const RECONNECT_MAX_MS = 5000;
  const PING_MS = 5000;
  const DISCONNECT_GRACE_MS = 4000;
  const UI_HIDE_MS = 3500;

  let ws = null;
  let pc = null;
  let state = 'connecting';
  let videoPlaying = false;
  let retryDelay = RECONNECT_MIN_MS;
  let reconnectTimer = null;
  let pingTimer = null;
  let graceTimer = null;
  let uiTimer = null;
  let remoteReady = false;
  let pendingCandidates = [];

  // ------------------------------------------------------------------ UI

  function setState(next) {
    state = next;
    statusEl.dataset.state = next;
    statusText.textContent = LABELS[next];
    updateOverlay();
    showUi();
  }

  function updateOverlay() {
    const live = state === 'connected' && videoPlaying;
    overlay.classList.toggle('hidden', live);
    overlay.classList.toggle('idle', state === 'disconnected');
    if (state === 'connecting') {
      overlayText.textContent = 'Connecting...';
    } else if (state === 'disconnected') {
      overlayText.textContent = 'Disconnected. Retrying...';
    } else if (!videoPlaying) {
      overlayText.textContent = 'Waiting for video...';
    }
  }

  function showUi() {
    document.body.classList.remove('hide-ui');
    clearTimeout(uiTimer);
    if (state === 'connected' && videoPlaying) {
      uiTimer = setTimeout(() => document.body.classList.add('hide-ui'), UI_HIDE_MS);
    }
  }

  ['pointermove', 'pointerdown', 'keydown'].forEach((evt) =>
    window.addEventListener(evt, showUi, { passive: true }));

  video.addEventListener('playing', () => { videoPlaying = true; playBtn.hidden = true; updateOverlay(); showUi(); });
  video.addEventListener('emptied', () => { videoPlaying = false; updateOverlay(); });

  function tryPlay() {
    const p = video.play();
    if (p && p.catch) {
      p.catch(() => {
        playBtn.hidden = false;
        overlay.classList.remove('hidden');
        overlayText.textContent = 'Video is ready';
      });
    }
  }
  playBtn.addEventListener('click', () => { tryPlay(); });

  // ---------------------------------------------------------- fullscreen

  const root = document.documentElement;
  const canFullscreen = !!(root.requestFullscreen || root.webkitRequestFullscreen || video.webkitEnterFullscreen);
  const inFullscreen = () => !!(document.fullscreenElement || document.webkitFullscreenElement);

  function toggleFullscreen() {
    if (inFullscreen()) {
      (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    } else if (root.requestFullscreen) {
      root.requestFullscreen({ navigationUI: 'hide' }).catch(() => {});
    } else if (root.webkitRequestFullscreen) {
      root.webkitRequestFullscreen();
    } else if (video.webkitEnterFullscreen) {
      video.webkitEnterFullscreen(); // iPhone Safari
    }
  }

  function syncFullscreenLabel() {
    fsBtn.textContent = inFullscreen() ? 'Exit fullscreen' : 'Fullscreen';
  }

  if (!canFullscreen) fsBtn.hidden = true;
  fsBtn.addEventListener('click', toggleFullscreen);
  document.addEventListener('fullscreenchange', syncFullscreenLabel);
  document.addEventListener('webkitfullscreenchange', syncFullscreenLabel);
  video.addEventListener('dblclick', toggleFullscreen);

  // ------------------------------------------------------------ signaling

  function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
  }

  function connect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
    setState('connecting');
    pendingCandidates = [];

    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    let socket;
    try {
      socket = new WebSocket(`${scheme}://${location.host}/ws`);
    } catch (err) {
      setState('disconnected');
      scheduleReconnect();
      return;
    }
    ws = socket;

    socket.onopen = () => {
      retryDelay = RECONNECT_MIN_MS;
      clearInterval(pingTimer);
      pingTimer = setInterval(() => send({ type: 'ping' }), PING_MS);
    };

    socket.onmessage = (ev) => {
      if (socket !== ws) return;
      handleMessage(ev.data).catch((err) => {
        console.error('Signaling error:', err);
        restart();
      });
    };

    socket.onclose = () => {
      if (socket !== ws) return;
      clearInterval(pingTimer);
      teardownPeer();
      setState('disconnected');
      scheduleReconnect();
    };

    socket.onerror = () => { /* onclose follows */ };
  }

  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(connect, retryDelay);
    retryDelay = Math.min(Math.round(retryDelay * 1.5), RECONNECT_MAX_MS);
  }

  function restart() {
    if (ws && ws.readyState <= WebSocket.OPEN) {
      ws.close();           // onclose tears down and reconnects
    } else {
      teardownPeer();
      setState('disconnected');
      scheduleReconnect();
    }
  }

  async function handleMessage(data) {
    const msg = JSON.parse(data);
    if (msg.type === 'offer') {
      await handleOffer(msg);
    } else if (msg.type === 'candidate') {
      await handleRemoteCandidate(msg);
    }
  }

  // --------------------------------------------------------------- WebRTC

  function lowLatency(receiver) {
    try {
      if ('playoutDelayHint' in receiver) receiver.playoutDelayHint = 0;
      if ('jitterBufferTarget' in receiver) receiver.jitterBufferTarget = 0;
    } catch (e) { /* optional hint */ }
  }

  async function handleOffer(msg) {
    teardownPeer();
    const peer = new RTCPeerConnection({ iceServers: [], bundlePolicy: 'max-bundle' });
    pc = peer;
    remoteReady = false;

    peer.ontrack = (e) => {
      const stream = e.streams[0] || new MediaStream([e.track]);
      if (video.srcObject !== stream) video.srcObject = stream;
      lowLatency(e.receiver);
      tryPlay();
    };

    peer.onicecandidate = (e) => {
      if (!e.candidate) return;
      send({
        type: 'candidate',
        candidate: e.candidate.candidate,
        sdpMid: e.candidate.sdpMid,
        sdpMLineIndex: e.candidate.sdpMLineIndex,
      });
    };

    peer.onconnectionstatechange = () => {
      if (peer !== pc) return;
      clearTimeout(graceTimer);
      switch (peer.connectionState) {
        case 'connected':
          setState('connected');
          break;
        case 'disconnected':
          setState('disconnected');
          graceTimer = setTimeout(() => { if (peer === pc && peer.connectionState !== 'connected') restart(); }, DISCONNECT_GRACE_MS);
          break;
        case 'failed':
        case 'closed':
          setState('disconnected');
          restart();
          break;
        default:
          setState('connecting');
      }
    };

    await peer.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
    remoteReady = true;
    const queued = pendingCandidates;
    pendingCandidates = [];
    for (const c of queued) await addCandidate(peer, c);

    const answer = await peer.createAnswer();
    await peer.setLocalDescription(answer);
    send({ type: 'answer', sdp: peer.localDescription.sdp });
  }

  async function handleRemoteCandidate(msg) {
    if (!msg.candidate) return;
    const init = { candidate: msg.candidate, sdpMid: msg.sdpMid, sdpMLineIndex: msg.sdpMLineIndex };
    if (pc && remoteReady) {
      await addCandidate(pc, init);
    } else {
      pendingCandidates.push(init);
    }
  }

  async function addCandidate(peer, init) {
    try {
      await peer.addIceCandidate(init);
    } catch (err) {
      console.warn('Ignoring ICE candidate:', err && err.message);
    }
  }

  function teardownPeer() {
    clearTimeout(graceTimer);
    remoteReady = false;
    if (pc) {
      const old = pc;
      pc = null;
      old.ontrack = old.onicecandidate = old.onconnectionstatechange = null;
      try { old.close(); } catch (e) { /* already closed */ }
    }
    if (video.srcObject) video.srcObject = null;
    videoPlaying = false;
    updateOverlay();
  }

  // ------------------------------------------------------------ lifecycle

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && (!ws || ws.readyState > WebSocket.OPEN)) {
      retryDelay = RECONNECT_MIN_MS;
      connect();
    }
  });

  window.addEventListener('beforeunload', () => {
    if (ws) { ws.onclose = null; ws.close(); }
  });

  connect();
})();
