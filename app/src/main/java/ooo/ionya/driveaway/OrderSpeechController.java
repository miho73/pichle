package ooo.ionya.driveaway;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import ai.pleos.playground.tts.constant.ErrorType;
import ai.pleos.playground.tts.constant.Mode;
import ai.pleos.playground.tts.listener.EventListener;
import ai.pleos.playground.tts.listener.OnInitializeListener;

import java.util.Locale;

/** Plays order text through Pleos Speech Service, with Android TTS as a compatibility fallback. */
public final class OrderSpeechController implements AutoCloseable {
    public interface Listener { void onStatus(String message); }
    /** Called once a requested playback ends: finished, failed or stopped. Main thread. */
    public interface FinishListener { void onFinished(boolean completed); }
    private enum Engine { STARTING_PLEOS, PLEOS, STARTING_ANDROID, ANDROID, FAILED, CLOSED }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final AudioFocusRequest focus;
    private Engine engine = Engine.STARTING_PLEOS;
    private ai.pleos.playground.tts.TextToSpeech pleos;
    private TextToSpeech androidTts;
    private String pendingText, activeText, utteranceId;
    private String failure = "음성 서비스를 사용할 수 없습니다.";
    private int generation;
    private boolean playbackStarted;
    private int volumeStream = -1, previousVolume = -1, requestedVolume = -1;
    private String volumeNotice = "";
    private FinishListener finishListener;
    private final Runnable volumeLock = new Runnable() {
        @Override public void run() {
            if (audio == null || volumeStream < 0 || engine == Engine.CLOSED) return;
            try { if (audio.getStreamVolume(volumeStream) != requestedVolume) audio.setStreamVolume(volumeStream, requestedVolume, 0); }
            catch (RuntimeException ignored) { }
            main.postDelayed(this, 250);
        }
    };

    private final EventListener pleosEvents = new EventListener() {
        @Override public void onReady() { }
        @Override public void onStart() { main.post(() -> {
            if (engine == Engine.PLEOS && activeText != null) started();
        }); }
        @Override public void onDone() { main.post(() -> {
            if (engine == Engine.PLEOS && activeText != null) completed();
        }); }
        @Override public void onError(ErrorType error) { main.post(() -> {
            if (engine == Engine.PLEOS || engine == Engine.STARTING_PLEOS) useAndroid();
        }); }
        @Override public void onStop() { }
        @Override public void onUpdatedRms(double rms) { }
    };

    public OrderSpeechController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                        main.post(this::stop);
                }, main).build();
        main.post(this::initializePleos);
    }

    private void initializePleos() {
        if (engine == Engine.CLOSED) return;
        status("음성 출력을 준비하고 있습니다.");
        int token = ++generation;
        try {
            pleos = new ai.pleos.playground.tts.TextToSpeech(context, Mode.ON_DEVICE);
            pleos.initialize(new OnInitializeListener() {
                @Override public void onSuccess() { main.post(() -> {
                    if (engine != Engine.STARTING_PLEOS) return;
                    try {
                        pleos.addEventListener(pleosEvents);
                        pleos.setAudioAttributes(attributes);
                        pleos.setLocale(Locale.KOREA);
                        engine = Engine.PLEOS;
                        ++generation;
                        status("한국어 음성 주문을 사용할 수 있습니다.");
                        playPending();
                    } catch (RuntimeException | LinkageError error) { useAndroid(); }
                }); }
                @Override public void onFailure(ErrorType error) { main.post(() -> {
                    if (engine == Engine.STARTING_PLEOS) useAndroid();
                }); }
            });
            main.postDelayed(() -> {
                if (generation == token && engine == Engine.STARTING_PLEOS) useAndroid();
            }, 6000);
        } catch (RuntimeException | LinkageError error) { useAndroid(); }
    }

    /** Requests are queued while initialization is in progress. All callbacks are delivered on main. */
    public void speak(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> speak(text)); return; }
        if (engine == Engine.CLOSED) return;
        if (text == null || text.trim().isEmpty()) { status("읽을 주문 내용이 없습니다."); return; }
        if (text.length() > TextToSpeech.getMaxSpeechInputLength()) {
            status("주문 문장이 너무 깁니다. 주문 내용을 줄여 주세요."); return;
        }
        if (activeText != null) { status("주문을 읽고 있습니다. 다시 들으려면 재생을 중지해 주세요."); return; }
        if (engine == Engine.FAILED) { status(failure); return; }
        volumeNotice = "";
        pendingText = text.trim();
        if (engine == Engine.PLEOS || engine == Engine.ANDROID) playPending();
        else status("음성 준비가 끝나면 주문을 읽습니다.");
    }

    private void playPending() {
        if (pendingText == null || engine == Engine.CLOSED) return;
        if (audio != null && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            pendingText = null;
            status("다른 소리가 재생 중입니다. 잠시 후 다시 시도해 주세요."); return;
        }
        activeText = pendingText;
        pendingText = null;
        playbackStarted = false;
        int token = ++generation;
        try {
            maximizeSpeechVolume();
            status("주문 음성을 준비하고 있습니다.");
            if (engine == Engine.PLEOS) pleos.speak(activeText, true);
            else if (engine == Engine.ANDROID) {
                utteranceId = "pichle-order-" + token;
                Bundle parameters = new Bundle();
                parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                if (androidTts.speak(activeText, TextToSpeech.QUEUE_FLUSH, parameters, utteranceId) == TextToSpeech.ERROR) {
                    failed("음성 재생을 시작하지 못했습니다. 음성 엔진과 차량 음량을 확인해 주세요."); return;
                }
            } else return;
            main.postDelayed(() -> {
                if (generation != token || activeText == null || playbackStarted) return;
                if (engine == Engine.PLEOS) useAndroid();
                else failed("음성 엔진이 응답하지 않습니다. 한국어 음성 데이터와 출력 장치를 확인해 주세요.");
            }, 10000);
        } catch (RuntimeException | LinkageError error) {
            if (engine == Engine.PLEOS) useAndroid();
            else failed("음성 재생을 시작하지 못했습니다. 음성 엔진 설정을 확인해 주세요.");
        }
    }

    private void useAndroid() {
        if (engine == Engine.CLOSED || engine == Engine.STARTING_ANDROID || engine == Engine.ANDROID) return;
        if (activeText != null) pendingText = activeText;
        activeText = null;
        engine = Engine.STARTING_ANDROID;
        ++generation;
        releasePleos();
        status("기기의 음성 출력을 준비하고 있습니다.");
        int token = generation;
        try {
            androidTts = new TextToSpeech(context, result -> main.post(() -> {
                if (engine != Engine.STARTING_ANDROID) return;
                if (result != TextToSpeech.SUCCESS) {
                    failed("음성 엔진이 없습니다. 기기에 한국어 음성 엔진을 설치해 주세요."); return;
                }
                int support = androidTts.setLanguage(Locale.KOREA);
                if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
                    failed("한국어 음성 데이터가 없습니다. 기기의 음성 출력 설정에서 한국어를 설치해 주세요."); return;
                }
                if (androidTts.getVoices() != null) {
                    for (Voice voice : androidTts.getVoices()) {
                        if ("ko".equals(voice.getLocale().getLanguage()) && !voice.isNetworkConnectionRequired()) {
                            androidTts.setVoice(voice); break;
                        }
                    }
                }
                androidTts.setAudioAttributes(attributes);
                androidTts.setSpeechRate(0.94f);
                androidTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { main.post(() -> {
                        if (engine == Engine.ANDROID && id.equals(utteranceId)) started();
                    }); }
                    @Override public void onDone(String id) { main.post(() -> {
                        if (engine == Engine.ANDROID && id.equals(utteranceId)) completed();
                    }); }
                    @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
                    @Override public void onError(String id, int code) { main.post(() -> {
                        if (engine == Engine.ANDROID && id.equals(utteranceId))
                            failed("주문 음성을 재생하지 못했습니다. 한국어 음성 데이터와 차량 음량을 확인해 주세요.");
                    }); }
                });
                engine = Engine.ANDROID;
                ++generation;
                status("한국어 음성 주문을 사용할 수 있습니다.");
                playPending();
            }));
            main.postDelayed(() -> {
                if (generation == token && engine == Engine.STARTING_ANDROID)
                    failed("기기의 음성 엔진이 응답하지 않습니다. 음성 출력 설정을 확인해 주세요.");
            }, 10000);
        } catch (RuntimeException error) { failed("기기의 음성 출력을 사용할 수 없습니다."); }
    }

    private void started() {
        playbackStarted = true;
        status("주문을 읽고 있습니다.");
        int token = generation;
        long limit = Math.max(30000L, activeText.length() * 350L + 15000L);
        main.postDelayed(() -> {
            if (generation != token || activeText == null) return;
            stop();
            status("음성 재생 응답이 끊겼습니다. 출력 장치를 확인한 뒤 다시 시도해 주세요.");
        }, limit);
    }

    private void completed() {
        ++generation;
        activeText = null;
        utteranceId = null;
        restoreSpeechVolume();
        abandonFocus();
        status("주문 읽기를 마쳤습니다.");
        notifyFinished(true);
    }

    private void failed(String message) {
        ++generation;
        pendingText = null;
        activeText = null;
        utteranceId = null;
        failure = message;
        if (engine == Engine.STARTING_ANDROID) engine = Engine.FAILED;
        if (androidTts != null) androidTts.stop();
        restoreSpeechVolume();
        abandonFocus();
        status(message);
        notifyFinished(false);
    }

    public void setFinishListener(FinishListener finishListener) { this.finishListener = finishListener; }

    private void notifyFinished(boolean completed) {
        if (finishListener != null) finishListener.onFinished(completed);
    }

    public boolean isBusy() { return activeText != null || pendingText != null; }

    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::stop); return; }
        if (engine == Engine.CLOSED) return;
        boolean hadRequest = activeText != null || pendingText != null;
        activeText = null;
        pendingText = null;
        utteranceId = null;
        if (engine == Engine.PLEOS || engine == Engine.ANDROID) ++generation;
        try { if (pleos != null) pleos.stop(); } catch (RuntimeException | LinkageError ignored) { }
        if (androidTts != null) androidTts.stop();
        restoreSpeechVolume();
        abandonFocus();
        if (hadRequest) { status("주문 읽기를 중지했습니다."); notifyFinished(false); }
    }

    /** Change volume only for an explicit playback request, never while preparing the engine. */
    private void maximizeSpeechVolume() {
        if (audio == null || volumeStream >= 0) return;
        try {
            if (audio.isVolumeFixed()) {
                volumeNotice = "차량 설정에서 음량을 조절해주세요.";
                return;
            }
            int stream = attributes.getVolumeControlStream();
            if (stream < 0) stream = AudioManager.STREAM_MUSIC;
            int maximum = audio.getStreamMaxVolume(stream);
            if (maximum <= 0) return;
            // Navigation speech uses the stream selected by its AudioAttributes, not the alarm/call stream.
            previousVolume = audio.getStreamVolume(stream);
            requestedVolume = maximum;
            volumeStream = stream;
            audio.setStreamVolume(stream, maximum, 0);
            if (audio.getStreamVolume(stream) != maximum)
                volumeNotice = "차량 설정에서 음량을 조절해주세요.";
            // Keep it at maximum for the whole playback, even if something lowers it.
            main.removeCallbacks(volumeLock);
            main.postDelayed(volumeLock, 250);
        } catch (RuntimeException error) {
            volumeNotice = "차량 설정에서 음량을 조절해주세요.";
        }
    }

    private void restoreSpeechVolume() {
        main.removeCallbacks(volumeLock);
        if (audio == null || volumeStream < 0) return;
        try {
            // The volume was held at maximum during playback, so put the original value back.
            if (previousVolume >= 0) audio.setStreamVolume(volumeStream, previousVolume, 0);
        } catch (RuntimeException ignored) {
            volumeNotice = "기존 음량은 차량 설정에서 조절해주세요.";
        } finally {
            volumeStream = -1;
            previousVolume = -1;
            requestedVolume = -1;
        }
    }

    private void abandonFocus() { if (audio != null) audio.abandonAudioFocusRequest(focus); }
    private void status(String message) {
        if (engine != Engine.CLOSED)
            listener.onStatus(message + (volumeNotice.isEmpty() ? "" : " " + volumeNotice));
    }
    private void releasePleos() {
        if (pleos == null) return;
        try { pleos.stop(); } catch (RuntimeException | LinkageError ignored) { }
        try { pleos.removeEventListener(pleosEvents); } catch (RuntimeException | LinkageError ignored) { }
        try { pleos.release(); } catch (RuntimeException | LinkageError ignored) { }
        pleos = null;
    }

    @Override public void close() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::close); return; }
        if (engine == Engine.CLOSED) return;
        stop();
        engine = Engine.CLOSED;
        main.removeCallbacksAndMessages(null);
        releasePleos();
        if (androidTts != null) { androidTts.stop(); androidTts.shutdown(); androidTts = null; }
    }
}
