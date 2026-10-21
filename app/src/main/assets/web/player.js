import { splitNalUnits, nalType, parseSps, initSegment, mediaSegment, TIMESCALE } from './mp4.js';

const TYPE_CONFIG = 1;
const TYPE_VIDEO = 2;
const TYPE_AUDIO = 3;

// Cushion between the playhead and the newest frame. It absorbs Wi-Fi jitter;
// raise it on a weak network with ?buffer=300 (milliseconds) in the page address.
const BUFFER_TARGET = Math.min(Math.max(Number(new URLSearchParams(location.search).get('buffer')) || 150, 30), 2000) / 1000;
const SPEEDUP_LAG = BUFFER_TARGET + 0.25;
const JUMP_LAG = BUFFER_TARGET + 1.0;

const $ = (id) => document.getElementById(id);
const video = $('video');
const MediaSourceImpl = window.ManagedMediaSource || window.MediaSource;

const state = {
  ws: null,
  reconnectDelay: 500,
  server: null, // last status message
  lastFrameAt: 0,
  lastTimestampUs: 0,
  frames: 0,
  bytes: 0,
};

// ---------------------------------------------------------------- video

class VideoPipeline {
  constructor() {
    this.sps = null;
    this.pps = null;
    this.codec = null;
    this.mediaSource = null;
    this.buffer = null;
    this.queue = [];
    this.sequence = 1;
    this.decodeTime = 0;
    this.pending = null; // frame waiting for its duration
    this.waitingForKeyframe = true;
    this.lastTrim = 0;
  }

  /** Codec parameters from the server; a change of SPS forces a fresh MediaSource. */
  configure(data) {
    let sps = null;
    let pps = null;
    for (const unit of splitNalUnits(data)) {
      if (nalType(unit) === 7) sps = unit;
      if (nalType(unit) === 8) pps = unit;
    }
    if (!sps || !pps) return;
    if (this.sps && equal(sps, this.sps) && equal(pps, this.pps)) return;
    this.sps = sps.slice();
    this.pps = pps.slice();
    this.reset();
  }

  reset() {
    this.queue = [];
    this.pending = null;
    this.sequence = 1;
    this.decodeTime = 0;
    this.waitingForKeyframe = true;
    this.buffer = null;
    this.lastTrim = 0;
    this.keyframeTimes = []; // decode times (s) of appended keyframes
    playback.buffering = true;
    if (!this.sps) return;
    const info = parseSps(this.sps);
    const mime = `video/mp4; codecs="${info.codec}"`;
    if (!MediaSourceImpl || !MediaSourceImpl.isTypeSupported(mime)) {
      showMessage(`Браузер не умеет проигрывать ${info.codec}. Откройте страницу в Chrome, Edge, Firefox или Safari 17+.`);
      return;
    }
    this.codec = info.codec;
    const mediaSource = new MediaSourceImpl();
    this.mediaSource = mediaSource;
    if (video.src) URL.revokeObjectURL(video.src);
    video.disableRemotePlayback = true; // required by ManagedMediaSource
    video.src = URL.createObjectURL(mediaSource);
    mediaSource.addEventListener('sourceopen', () => {
      if (this.mediaSource !== mediaSource) return;
      const buffer = mediaSource.addSourceBuffer(mime);
      buffer.mode = 'segments';
      buffer.addEventListener('updateend', () => this.pump());
      buffer.addEventListener('error', () => this.fail('sourcebuffer error'));
      this.buffer = buffer;
      this.queue.unshift(initSegment(this.sps, this.pps, info));
      this.pump();
    }, { once: true });
    $('resolution').textContent = `${info.width}×${info.height}`;
  }

  /** A frame (access unit) in Annex-B form with its capture timestamp in microseconds. */
  frame(data, keyframe, timestampUs) {
    if (!this.sps) return;
    if (this.waitingForKeyframe) {
      if (!keyframe) return;
      this.waitingForKeyframe = false;
    }
    const units = [];
    for (const unit of splitNalUnits(data)) {
      const type = nalType(unit);
      if (type === 7 || type === 8) {
        // Inline parameter sets: reinitialize if the stream changed shape.
        if (type === 7 && !equal(unit, this.sps)) {
          this.configure(data);
          if (equal(unit, this.sps)) return this.frame(data, keyframe, timestampUs);
        }
        continue;
      }
      if (type === 9) continue; // access unit delimiter
      units.push(unit);
    }
    if (!units.length) return;
    const current = { units, keyframe, timestampUs };
    const previous = this.pending;
    this.pending = current;
    if (!previous) return;
    // Durations come from arrival spacing so the timeline has no gaps.
    let duration = Math.round(((timestampUs - previous.timestampUs) / 1e6) * TIMESCALE);
    duration = Math.min(Math.max(duration, TIMESCALE / 240), TIMESCALE / 10);
    if (previous.keyframe) this.keyframeTimes.push(this.decodeTime / TIMESCALE);
    this.queue.push(mediaSegment(this.sequence++, this.decodeTime, duration, previous.units, previous.keyframe));
    this.decodeTime += duration;
    this.pump();
  }

  pump() {
    const buffer = this.buffer;
    if (!buffer || buffer.updating || !this.queue.length) return;
    if (this.queue.length > 120) {
      // The tab was throttled or decoding cannot keep up; start over from a keyframe.
      this.fail('queue overflow');
      return;
    }
    try {
      // Drop old video, but only up to a keyframe: removing a keyframe makes the
      // browser discard every frame that depends on it, i.e. the whole picture.
      const now = video.currentTime;
      const cut = this.keyframeTimes.filter((t) => t <= now - 3).pop();
      if (cut && cut - this.lastTrim > 5) {
        this.lastTrim = cut;
        this.keyframeTimes = this.keyframeTimes.filter((t) => t >= cut);
        buffer.remove(0, cut);
        return;
      }
      buffer.appendBuffer(this.queue.shift());
    } catch (e) {
      this.fail(e.name || String(e));
    }
  }

  fail(reason) {
    console.warn('Video pipeline reset:', reason);
    stalls.begin(`перезапуск плеера: ${reason}`);
    this.reset();
    requestKeyframe();
  }

  /** Seconds between the playhead and the newest buffered frame (0 when nothing is buffered). */
  lag() {
    const b = video.buffered;
    if (!b.length) return 0;
    return Math.max(0, b.end(b.length - 1) - video.currentTime);
  }
}

const pipeline = new VideoPipeline();

function equal(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

// ---------------------------------------------------------------- pauses

/**
 * Remembers why the picture stopped, so the stats panel can tell a slow network
 * from a headset that stopped sending frames.
 */
const stalls = {
  count: 0,
  last: null, // { seconds, cause }
  current: null, // { start, cause, maxArrivalGap, maxHeadsetGap, dropped }

  begin(cause = null) {
    if (this.current) {
      if (cause && !this.current.cause) this.current.cause = cause;
      return;
    }
    this.current = { start: performance.now(), cause, maxArrivalGap: 0, maxHeadsetGap: 0, dropped: false };
  },

  frameArrived(arrivalGapMs, headsetGapMs) {
    if (!this.current) return;
    this.current.maxArrivalGap = Math.max(this.current.maxArrivalGap, arrivalGapMs);
    this.current.maxHeadsetGap = Math.max(this.current.maxHeadsetGap, headsetGapMs);
  },

  dropped() {
    this.begin();
    this.current.dropped = true;
  },

  end() {
    const c = this.current;
    if (!c) return;
    this.current = null;
    const ms = performance.now() - c.start;
    if (ms < 250) return; // a hiccup nobody notices
    let cause = c.cause;
    if (c.dropped) cause = 'сеть до этого устройства не успевала, шлем пропустил для него кадры';
    else if (!cause && c.maxHeadsetGap > ms * 0.6) cause = 'шлем не присылал кадры';
    else if (!cause && c.maxArrivalGap > 150) cause = 'кадры задерживались в сети (Wi-Fi)';
    else if (!cause) cause = 'браузер не успевал показывать кадры';
    this.count++;
    this.last = { seconds: ms / 1000, cause };
    console.info(`Пауза ${(ms / 1000).toFixed(1)} с: ${cause}`);
  },

  /** Seconds the picture has been standing still, 0 when playing. */
  ongoing() {
    return this.current ? (performance.now() - this.current.start) / 1000 : 0;
  },
};

// ---------------------------------------------------------------- playback control

const playback = { buffering: true, blocked: false, lastTime: -1, lastMove: 0, lastKeyframeRequest: 0 };

function tryPlay() {
  video.play().then(() => {
    playback.blocked = false;
  }).catch((e) => {
    // Some browsers (iOS in low power mode) refuse even muted autoplay until a tap.
    if (e.name === 'NotAllowedError') playback.blocked = true;
  });
}

document.addEventListener('pointerdown', () => {
  if (playback.blocked) tryPlay();
});

/** Pauses when the cushion runs dry and resumes once it is refilled; keeps latency near BUFFER_TARGET. */
function controlPlayback() {
  const now = performance.now();
  const b = video.buffered;
  if (document.hidden) {
    playback.lastMove = now;
    return;
  }
  if (!b.length) {
    // Frames arrive and are appended, yet nothing is buffered: the buffer was lost.
    if (pipeline.buffer && !pipeline.waitingForKeyframe && now - state.lastFrameAt < 500) {
      playback.emptySince = playback.emptySince || now;
      if (now - playback.emptySince > 1500) {
        playback.emptySince = 0;
        pipeline.fail('буфер потерян');
        return;
      }
    }
    // Nothing decodable yet (fresh pipeline waiting for a keyframe): keep asking.
    playback.buffering = true;
    if (state.lastFrameAt && now - state.lastFrameAt < 1000) stalls.begin();
    if (pipeline.waitingForKeyframe && now - playback.lastKeyframeRequest > 1000) {
      playback.lastKeyframeRequest = now;
      requestKeyframe();
    }
    return;
  }
  playback.emptySince = 0;
  const end = b.end(b.length - 1);
  if (video.currentTime < b.start(0) || end - video.currentTime > JUMP_LAG) {
    video.currentTime = Math.max(b.start(b.length - 1), end - BUFFER_TARGET);
  }
  const lag = pipeline.lag();
  if (video.currentTime !== playback.lastTime) {
    playback.lastTime = video.currentTime;
    playback.lastMove = now;
  }
  const stillFor = now - playback.lastMove;
  if (playback.buffering) {
    if (!video.paused) video.pause(); // the cushion first, then the picture
    if (lag >= BUFFER_TARGET) {
      playback.buffering = false;
      playback.lastMove = now;
      tryPlay();
      stalls.end();
      return; // the watchdog below measures from this moment on
    }
  } else if (!playback.blocked && (lag < 0.03 || (lag < 0.1 && stillFor > 200))) {
    // Ran dry (Chrome halts a frame or two before the end): wait for a full
    // cushion instead of stuttering frame by frame.
    playback.buffering = true;
    video.pause();
    stalls.begin();
  } else if (video.paused && !playback.blocked) {
    tryPlay();
  }
  if (lag > SPEEDUP_LAG) video.playbackRate = 1.1;
  else if (lag < BUFFER_TARGET + 0.05) video.playbackRate = 1.0;

  // Watchdog: frames keep arriving and are buffered ahead, but the playhead does not move -> rebuild.
  const feeding = lag >= 0.1 && now - state.lastFrameAt < 500;
  if (!playback.buffering && !playback.blocked && feeding && stillFor > 1500) {
    playback.lastMove = now;
    pipeline.fail('воспроизведение зависло');
  }
}
setInterval(controlPlayback, 100);

// The headset encodes keyframes only on request; one every 10 s lets old video be dropped.
setInterval(() => {
  if (!document.hidden && !pipeline.waitingForKeyframe) requestKeyframe();
}, 10000);

// Hidden tabs lose their video track; after coming back, restart from a fresh keyframe.
let hiddenAt = 0;
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    hiddenAt = performance.now();
  } else if (hiddenAt && performance.now() - hiddenAt > 1000) {
    pipeline.fail('вкладка была скрыта');
  }
});

// ---------------------------------------------------------------- audio

class AudioPipeline {
  constructor() {
    this.context = null;
    this.gain = null;
    this.playTime = 0;
    this.enabled = false;
  }

  enable() {
    if (!this.context) {
      const Context = window.AudioContext || window.webkitAudioContext;
      this.context = new Context({ latencyHint: 'interactive' });
      this.gain = this.context.createGain();
      this.gain.gain.value = Number($('volume').value);
      this.gain.connect(this.context.destination);
    }
    this.context.resume();
    this.enabled = true;
    this.playTime = 0;
  }

  disable() {
    this.enabled = false;
    if (this.context) this.context.suspend();
  }

  setVolume(value) {
    if (this.gain) this.gain.gain.value = value;
  }

  /** Big-endian 16-bit interleaved PCM. */
  push(view, offset, channels, sampleRate) {
    if (!this.enabled || !this.context || channels < 1) return;
    const frames = Math.floor((view.byteLength - offset) / 2 / channels);
    if (!frames) return;
    const buffer = this.context.createBuffer(channels, frames, sampleRate);
    for (let c = 0; c < channels; c++) {
      const out = buffer.getChannelData(c);
      for (let i = 0; i < frames; i++) out[i] = view.getInt16(offset + (i * channels + c) * 2, false) / 32768;
    }
    // Delay audio by roughly the video lag so the two stay close; keep at least
    // 100 ms queued because the headset delivers audio in ~85 ms chunks.
    const delay = Math.min(Math.max(pipeline.lag(), 0.1), 0.5);
    const now = this.context.currentTime;
    if (this.playTime < now + delay - 0.06) this.playTime = now + delay;
    if (this.playTime > now + delay + 0.12) return; // too far ahead: drop a chunk to catch up
    const source = this.context.createBufferSource();
    source.buffer = buffer;
    source.connect(this.gain);
    source.start(this.playTime);
    this.playTime += buffer.duration;
  }
}

const audio = new AudioPipeline();

// Handy for poking at the player from the browser console.
window.webstream = { pipeline, audio, state, video };

// ---------------------------------------------------------------- network

function getCode() {
  const urlCode = new URLSearchParams(location.search).get('code');
  if (urlCode) return urlCode.trim();
  return sessionStorage.getItem('streamCode') || '';
}

function showAuthDialog(errorMsg) {
  $('auth-dialog').hidden = false;
  if (errorMsg) {
    $('auth-error').textContent = errorMsg;
    $('auth-error').hidden = false;
  } else {
    $('auth-error').hidden = true;
  }
  setTimeout(() => $('auth-code').focus(), 100);
}

function hideAuthDialog() {
  $('auth-dialog').hidden = true;
  $('auth-error').hidden = true;
}

$('auth-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const code = $('auth-code').value.trim();
  if (code.length === 6) {
    $('auth-error').hidden = true;
    connect(code);
  } else {
    showAuthDialog('Введите 6-значный код');
  }
});

function connect(providedCode) {
  const code = providedCode || getCode();
  if (!code) {
    showAuthDialog();
    return;
  }
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${scheme}://${location.host}/ws?code=${encodeURIComponent(code)}`);
  ws.binaryType = 'arraybuffer';
  state.ws = ws;
  let opened = false;
  setConnection('connecting');

  ws.onopen = () => {
    opened = true;
    state.reconnectDelay = 500;
    sessionStorage.setItem('streamCode', code);
    hideAuthDialog();
    // The server starts every connection at a keyframe; frames in between were lost.
    pipeline.waitingForKeyframe = true;
    setConnection('open');
  };
  ws.onmessage = (event) => {
    if (typeof event.data === 'string') {
      const message = JSON.parse(event.data);
      if (message.type === 'dropped') stalls.dropped();
      else handleStatus(message);
      return;
    }
    const view = new DataView(event.data);
    const type = view.getUint8(0);
    const flags = view.getUint8(1);
    const channels = view.getUint8(2);
    const sampleRate = view.getUint32(4, false);
    const timestampUs = Number(view.getBigInt64(8, false));
    const payload = new Uint8Array(event.data, 16);
    if (type === TYPE_CONFIG) {
      pipeline.configure(payload);
    } else if (type === TYPE_VIDEO) {
      const now = performance.now();
      if (state.lastFrameAt) stalls.frameArrived(now - state.lastFrameAt, (timestampUs - state.lastTimestampUs) / 1000);
      state.lastFrameAt = now;
      state.lastTimestampUs = timestampUs;
      state.frames++;
      state.bytes += payload.length;
      pipeline.frame(payload, (flags & 1) !== 0, timestampUs);
    } else if (type === TYPE_AUDIO) {
      audio.push(view, 16, channels, sampleRate);
    }
  };
  ws.onclose = () => {
    if (state.ws !== ws) return;
    setConnection('closed');
    if (!opened) {
      sessionStorage.removeItem('streamCode');
      showAuthDialog('Неверный код подключения');
      return;
    }
    setTimeout(() => connect(code), state.reconnectDelay);
    state.reconnectDelay = Math.min(state.reconnectDelay * 2, 5000);
  };
}

function requestKeyframe() {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send('{"type":"keyframe"}');
}

// ---------------------------------------------------------------- UI

const STATE_TEXT = {
  IDLE: 'Трансляция остановлена',
  STARTING: 'Запуск сервиса кастинга…',
  WAITING_FOR_HEADSET: 'Ждём подключения шлема…',
  CONNECTED: 'Шлем подключён, ждём кадры…',
  STREAMING: 'В эфире',
  ERROR: 'Нет связи со шлемом',
};

function handleStatus(status) {
  if (status.type !== 'status') return;
  state.server = status;
  $('viewers').textContent = status.viewers;
  $('audio-button').hidden = !status.audio;
  render();
}

function setConnection(value) {
  state.connection = value;
  render();
}

function render() {
  const s = state.server;
  const stalled = performance.now() - state.lastFrameAt > 2500;
  let text;
  let live = false;
  if (playback.blocked) {
    text = 'Нажмите на экран, чтобы запустить видео';
  } else if (state.connection !== 'open') {
    text = state.connection === 'connecting' ? 'Подключение…' : 'Нет связи со шлемом, переподключаемся…';
  } else if (!s) {
    text = 'Подключение…';
  } else if (s.state === 'STREAMING' && !stalled && stalls.ongoing() > 0.7) {
    text = 'Догоняем трансляцию…';
  } else if (s.state === 'STREAMING' && !stalled) {
    text = 'В эфире';
    live = true;
  } else if (s.state === 'STREAMING') {
    text = 'Нет кадров: шлем, похоже, спит или снят';
  } else {
    text = STATE_TEXT[s.state] || s.state;
  }
  $('status-text').textContent = text;
  $('status').classList.toggle('live', live);
  $('overlay').hidden = live;
  $('overlay-text').textContent = s && s.detail && !live ? `${text}. ${s.detail}` : text;
}

function showMessage(text) {
  $('overlay').hidden = false;
  $('overlay-text').textContent = text;
}

setInterval(() => {
  $('fps').textContent = state.frames;
  $('bitrate').textContent = ((state.bytes * 8) / 1e6).toFixed(1);
  $('latency').textContent = video.buffered.length ? `${Math.round(pipeline.lag() * 1000)} мс` : '—';
  $('pauses').textContent = stalls.last
    ? `${stalls.count}, последняя ${stalls.last.seconds.toFixed(1)} с: ${stalls.last.cause}`
    : 'не было';
  state.frames = 0;
  state.bytes = 0;
  render();
}, 1000);

$('audio-button').addEventListener('click', () => {
  if (audio.enabled) {
    audio.disable();
  } else {
    audio.enable();
  }
  $('audio-button').classList.toggle('on', audio.enabled);
  $('audio-button').setAttribute('aria-pressed', String(audio.enabled));
  $('audio-label').textContent = audio.enabled ? 'Звук включён' : 'Включить звук';
});

$('volume').addEventListener('input', (e) => audio.setVolume(Number(e.target.value)));

$('fullscreen-button').addEventListener('click', () => {
  const stage = $('stage');
  if (document.fullscreenElement) {
    document.exitFullscreen();
  } else if (stage.requestFullscreen) {
    stage.requestFullscreen();
  } else if (video.webkitEnterFullscreen) {
    video.webkitEnterFullscreen();
  }
});

$('stats-button').addEventListener('click', () => {
  $('stats').hidden = !$('stats').hidden;
});

// Hide controls when the pointer rests.
let idleTimer = 0;
function wake() {
  document.body.classList.remove('idle');
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => document.body.classList.add('idle'), 3000);
}
['pointermove', 'pointerdown', 'keydown'].forEach((e) => document.addEventListener(e, wake));
wake();

document.addEventListener('keydown', (e) => {
  if (e.key === 'f' || e.key === 'а') $('fullscreen-button').click();
  if (e.key === 'm' || e.key === 'ь') $('audio-button').click();
});

if (!MediaSourceImpl) {
  showMessage('Этот браузер не поддерживает Media Source Extensions. Откройте страницу в Chrome, Edge, Firefox или Safari 17+.');
} else {
  connect();
}
