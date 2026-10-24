package com.sslauncher.multiplayer;

import com.sslauncher.core.Config;
import com.sslauncher.core.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Room-scoped push-to-talk-free voice chat.
 *
 * <p>Voice is a full mesh over the existing P2P node: while a player is in a
 * room, the launcher opens one outbound {@code /sslauncher/voice/1.0.0} stream
 * to every other member and accepts one inbound stream from each. The
 * signalling server only exchanges room membership and addresses; audio never
 * passes through it and never passes through the room host.</p>
 *
 * <p>Each pair of players therefore has two independent unidirectional
 * streams. That keeps the protocol trivial (no half-close negotiation) and
 * lets the responder start reading as soon as the stream is accepted.</p>
 */
public final class VoiceChatService {
    private static final VoiceChatService INSTANCE = new VoiceChatService();
    private static final long RETRY_SECONDS = 4;
    private static final long SAVE_DELAY_MS = 400;

    public static VoiceChatService get() {
        return INSTANCE;
    }

    public enum State {
        /** The user turned voice chat off. */
        OFF,
        /** Enabled, but not connected to a room (or audio is still starting). */
        IDLE,
        /** At least one voice stream is being established. */
        CONNECTING,
        /** Audio is flowing with one or more room members. */
        READY,
        /** The local audio devices could not be opened. */
        ERROR
    }

    /** One player as shown in the voice controls. */
    public record VoicePeer(String peerId, String name, boolean host, boolean self,
                            boolean muted, boolean linked) {
    }

    public record Snapshot(State state, String detail, boolean enabled, boolean active,
                           boolean micMuted, boolean micAvailable, int inputVolume,
                           int outputVolume, int linkedPeers, List<VoicePeer> peers) {
        public Snapshot {
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }

    private final VoiceAudioEngine audio = new VoiceAudioEngine();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ss-voice");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    /** Outbound streams: we write our microphone to these peers. */
    private final ConcurrentHashMap<String, VoiceStream> outgoing = new ConcurrentHashMap<>();
    /** Inbound streams: we read these peers' microphones. */
    private final ConcurrentHashMap<String, VoiceStream> incoming = new ConcurrentHashMap<>();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    /** PeerIDs muted by the user; kept across rooms for the current session. */
    private final Set<String> muted = ConcurrentHashMap.newKeySet();
    private final AtomicLong saveGeneration = new AtomicLong();
    private final Consumer<MultiplayerService.Snapshot> roomListener;

    private volatile MultiplayerService.Snapshot room;
    private volatile Snapshot snapshot;
    private volatile boolean active;
    private volatile boolean audioFailed;
    private volatile String audioError = "";
    private final Map<String, String> connectErrors = new ConcurrentHashMap<>();
    private volatile boolean shutdown;

    private VoiceChatService() {
        this.room = MultiplayerService.get().snapshot();
        audio.setInputVolume(Config.get().voiceInputVolume);
        audio.setOutputVolume(Config.get().voiceOutputVolume);
        audio.setMicMuted(Config.get().voiceMicMuted);
        this.snapshot = buildSnapshot();
        this.roomListener = next -> {
            room = next;
            run(this::reconcile);
        };
        MultiplayerService.get().addListener(roomListener);
    }

    // ------------------------------------------------------------------ state

    public Snapshot snapshot() {
        return snapshot;
    }

    public void addListener(Consumer<Snapshot> listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        try {
            listener.accept(snapshot);
        } catch (Throwable ignored) {
            // A broken UI listener must not affect audio.
        }
    }

    public void removeListener(Consumer<Snapshot> listener) {
        listeners.remove(listener);
    }

    private void publish() {
        Snapshot next = buildSnapshot();
        snapshot = next;
        for (Consumer<Snapshot> listener : listeners) {
            try {
                listener.accept(next);
            } catch (Throwable t) {
                Log.warn("语音：状态监听器异常: " + t.getMessage());
            }
        }
    }

    private Snapshot buildSnapshot() {
        MultiplayerService.Snapshot current = room;
        String selfId = MultiplayerService.get().snapshot().peerId();
        List<VoicePeer> peers = new ArrayList<>();
        int linked = 0;
        boolean missingAddresses = false;
        if (current != null) {
            for (MultiplayerService.RoomMember member : current.members()) {
                boolean self = member.self() || member.peerId().equals(selfId);
                // Two-way audio is ready only when both unidirectional
                // streams exist (we can hear them and they can hear us).
                boolean isLinked = self
                        || (incoming.containsKey(member.peerId())
                        && outgoing.containsKey(member.peerId()));
                if (isLinked && !self) {
                    linked++;
                }
                if (!self && member.addresses().isEmpty() && member.udp() == null) {
                    missingAddresses = true;
                }
                peers.add(new VoicePeer(member.peerId(), member.name(), member.host(), self,
                        muted.contains(member.peerId()), isLinked));
            }
        }
        boolean enabled = Config.get().voiceChatEnabled;
        boolean inRoom = current != null && current.inRoom();
        State state;
        String detail;
        if (!enabled) {
            state = State.OFF;
            detail = "语音已关闭";
        } else if (!inRoom) {
            state = State.IDLE;
            detail = "加入房间后自动连接语音";
        } else if (audioFailed) {
            state = State.ERROR;
            detail = audioError.isBlank() ? "无法启动音频设备" : audioError;
        } else if (!active) {
            state = State.IDLE;
            detail = "正在准备音频设备…";
        } else if (linked > 0) {
            state = State.READY;
            detail = "语音聊天中 · " + (linked + 1) + " 人";
        } else if (!connecting.isEmpty()) {
            state = State.CONNECTING;
            detail = "正在建立语音连接…";
        } else if (peers.size() > 1 && missingAddresses) {
            state = State.ERROR;
            detail = "部分玩家缺少语音地址，需要升级引导服务器";
        } else if (peers.size() > 1 && !connectErrors.isEmpty()) {
            state = State.ERROR;
            detail = "无法直连部分玩家：TCP 直连与 UDP 打洞均未成功";
        } else {
            state = State.IDLE;
            detail = peers.size() <= 1 ? "等待好友加入房间" : "正在等待语音连接…";
        }
        return new Snapshot(state, detail, enabled, active,
                Config.get().voiceMicMuted, audio.micAvailable(),
                Config.get().voiceInputVolume, Config.get().voiceOutputVolume,
                linked, peers);
    }

    private void run(Runnable task) {
        if (shutdown) {
            return;
        }
        try {
            exec.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    // --------------------------------------------------------------- lifecycle

    /** Called by {@link MultiplayerService} whenever the room roster changes. */
    private void reconcile() {
        if (shutdown) {
            return;
        }
        MultiplayerService.Snapshot current = room;
        boolean shouldRun = Config.get().voiceChatEnabled && current != null && current.inRoom();
        if (!shouldRun) {
            stopVoice(current != null && current.inRoom()
                    ? "语音已关闭" : "加入房间后自动连接语音");
            publish();
            return;
        }
        if (!active && !audioFailed) {
            // Mark active first so an inbound stream arriving while the audio
            // device is opening is accepted instead of rejected.
            active = true;
            if (!audio.start(this::broadcast)) {
                active = false;
                audioFailed = true;
                audioError = audio.error();
                Log.warn("语音：" + audioError);
            } else {
                audio.setInputVolume(Config.get().voiceInputVolume);
                audio.setOutputVolume(Config.get().voiceOutputVolume);
                audio.setMicMuted(Config.get().voiceMicMuted);
            }
        }
        Set<String> members = new LinkedHashSet<>();
        for (MultiplayerService.RoomMember member : current.members()) {
            if (!member.self()) {
                members.add(member.peerId());
            }
        }
        closeSessionsOutside(members);
        connectErrors.keySet().retainAll(members);
        if (active) {
            for (MultiplayerService.RoomMember member : current.members()) {
                if (member.self()) {
                    continue;
                }
                audio.addReceiver(member.peerId());
                audio.setMuted(member.peerId(), muted.contains(member.peerId()));
                if (outgoing.containsKey(member.peerId())
                        || connecting.contains(member.peerId())
                        || (member.addresses().isEmpty() && member.udp() == null)) {
                    continue;
                }
                connect(member);
            }
        }
        publish();
    }

    private void connect(MultiplayerService.RoomMember member) {
        String peerId = member.peerId();
        if (!connecting.add(peerId)) {
            return;
        }
        Log.info("语音：正在连接 " + shortId(peerId));
        MultiplayerService.get().openVoiceStream(peerId, member.addresses())
                .whenComplete((session, error) -> {
                    connecting.remove(peerId);
                    if (error != null) {
                        String message = friendly(error);
                        connectErrors.put(peerId, message);
                        Log.warn("语音：连接 " + shortId(peerId) + " 失败: " + message);
                        scheduleRetry(peerId);
                        publish();
                        return;
                    }
                    connectErrors.remove(peerId);
                    if (!active || !MultiplayerService.get().isRoomMember(peerId)) {
                        session.close();
                        return;
                    }
                    // Register the close handler after publishing the session in
                    // the map: if the stream died while the dial was completing,
                    // the handler fires immediately and can still remove it.
                    VoiceStream previous = outgoing.put(peerId, session);
                    session.setCloseHandler(() -> onOutgoingClosed(peerId, session));
                    if (previous != null && previous != session) {
                        previous.close();
                    }
                    Log.info((session.isClosed() ? "语音：连接立即断开，稍后重试 "
                            : "语音：已连接 ") + shortId(peerId));
                    publish();
                });
    }

    private void scheduleRetry(String peerId) {
        if (shutdown || !active) {
            return;
        }
        try {
            exec.schedule(() -> {
                if (!active || shutdown
                        || !MultiplayerService.get().isRoomMember(peerId)
                        || outgoing.containsKey(peerId)
                        || connecting.contains(peerId)) {
                    return;
                }
                MultiplayerService.RoomMember member = findMember(peerId);
                if (member != null
                        && (!member.addresses().isEmpty() || member.udp() != null)) {
                    connect(member);
                }
            }, RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    private void onOutgoingClosed(String peerId, VoiceStream session) {
        if (!outgoing.remove(peerId, session)) {
            return;
        }
        Log.info("语音：与 " + shortId(peerId) + " 的发送流已关闭");
        if (active && !shutdown && MultiplayerService.get().isRoomMember(peerId)) {
            scheduleRetry(peerId);
        }
        publish();
    }

    /**
     * Accepts the peer's outbound stream. Only room members are accepted, which
     * is what enforces "voice works only inside the same room".
     */
    void onInboundStream(VoiceStream session) {
        String peer = session.remotePeerId();
        if (shutdown || !active || !MultiplayerService.get().isRoomMember(peer)) {
            Log.warn("语音：拒绝来自 " + shortId(peer) + " 的语音流（不在当前房间）");
            session.close();
            return;
        }
        if (session.isClosed()) {
            return;
        }
        session.setFrameHandler(packet -> {
            // Re-check membership per packet: a peer who just left must stop
            // being audible immediately, even before the stream is torn down.
            if (incoming.get(peer) == session
                    && MultiplayerService.get().isRoomMember(peer)) {
                audio.pushPacket(peer, packet);
            }
        });
        // Publish before installing the close callback so an already-closed
        // stream is removed again instead of lingering in the map.
        VoiceStream previous = incoming.put(peer, session);
        session.setCloseHandler(() -> onIncomingClosed(peer, session));
        if (previous != null && previous != session) {
            previous.close();
        }
        if (!session.isClosed()) {
            audio.addReceiver(peer);
            audio.setMuted(peer, muted.contains(peer));
        }
        publish();
    }

    private void onIncomingClosed(String peerId, VoiceStream session) {
        if (!incoming.remove(peerId, session)) {
            return;
        }
        audio.removeReceiver(peerId);
        publish();
    }

    private void broadcast(byte[] packet) {
        for (VoiceStream session : outgoing.values()) {
            try {
                session.writeFrame(packet);
            } catch (Exception e) {
                // The close handler removes the stream and schedules a retry.
                session.close();
            }
        }
    }

    private void closeSessionsOutside(Set<String> members) {
        for (var entry : outgoing.entrySet()) {
            if (!members.contains(entry.getKey())) {
                entry.getValue().close();
                outgoing.remove(entry.getKey(), entry.getValue());
                audio.removeReceiver(entry.getKey());
                connectErrors.remove(entry.getKey());
            }
        }
        for (var entry : incoming.entrySet()) {
            if (!members.contains(entry.getKey())) {
                entry.getValue().close();
                incoming.remove(entry.getKey(), entry.getValue());
                audio.removeReceiver(entry.getKey());
            }
        }
    }

    private void stopVoice(String detail) {
        if (!active && !audioFailed && outgoing.isEmpty() && incoming.isEmpty()
                && connecting.isEmpty()) {
            return;
        }
        active = false;
        audioFailed = false;
        audioError = "";
        for (VoiceStream session : outgoing.values()) {
            session.close();
        }
        outgoing.clear();
        for (VoiceStream session : incoming.values()) {
            session.close();
        }
        incoming.clear();
        connecting.clear();
        connectErrors.clear();
        audio.stop();
        detail = detail == null ? "" : detail;
        if (!detail.isEmpty()) {
            Log.info("语音：" + detail);
        }
    }

    private MultiplayerService.RoomMember findMember(String peerId) {
        MultiplayerService.Snapshot current = room;
        if (current == null) {
            return null;
        }
        for (MultiplayerService.RoomMember member : current.members()) {
            if (member.peerId().equals(peerId)) {
                return member;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- controls

    public void setEnabled(boolean enabled) {
        Config cfg = Config.get();
        if (cfg.voiceChatEnabled == enabled) {
            return;
        }
        cfg.voiceChatEnabled = enabled;
        Config.save();
        run(this::reconcile);
        publish();
    }

    public void setInputVolume(int percent) {
        int value = Math.max(0, Math.min(200, percent));
        Config cfg = Config.get();
        if (cfg.voiceInputVolume == value) {
            return;
        }
        cfg.voiceInputVolume = value;
        audio.setInputVolume(value);
        scheduleSave();
        publish();
    }

    public void setOutputVolume(int percent) {
        int value = Math.max(0, Math.min(200, percent));
        Config cfg = Config.get();
        if (cfg.voiceOutputVolume == value) {
            return;
        }
        cfg.voiceOutputVolume = value;
        audio.setOutputVolume(value);
        scheduleSave();
        publish();
    }

    public void setMicMuted(boolean mutedNow) {
        Config cfg = Config.get();
        if (cfg.voiceMicMuted == mutedNow) {
            return;
        }
        cfg.voiceMicMuted = mutedNow;
        audio.setMicMuted(mutedNow);
        Config.save();
        publish();
    }

    public boolean isMuted(String peerId) {
        return muted.contains(peerId);
    }

    public void setMuted(String peerId, boolean mute) {
        if (peerId == null || peerId.isBlank()) {
            return;
        }
        if (mute) {
            muted.add(peerId);
        } else {
            muted.remove(peerId);
        }
        audio.setMuted(peerId, mute);
        publish();
    }

    public void toggleMuted(String peerId) {
        setMuted(peerId, !muted.contains(peerId));
    }

    /** True while that peer's decoded audio is above the speech threshold. */
    public boolean isSpeaking(String peerId) {
        return active && audio.isSpeaking(peerId);
    }

    public boolean isSelfSpeaking() {
        return active && audio.isSelfSpeaking();
    }

    private void scheduleSave() {
        final long generation = saveGeneration.incrementAndGet();
        try {
            exec.schedule(() -> {
                if (saveGeneration.get() == generation) {
                    Config.save();
                }
            }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            Config.save();
        }
    }

    /** Releases the audio devices and socket streams before the launcher exits. */
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        if (roomListener != null) {
            MultiplayerService.get().removeListener(roomListener);
        }
        stopVoice("语音已关闭");
        Config.save();
        exec.shutdownNow();
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 10) {
            return String.valueOf(id);
        }
        return id.substring(0, 6) + "…" + id.substring(id.length() - 4);
    }

    private static String friendly(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
