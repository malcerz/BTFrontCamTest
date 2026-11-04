package com.malcerz.btfrontcamtest;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioDeviceInfo;
import android.media.AudioRecordingConfiguration;
import android.media.AudioFormat;
import android.media.audiofx.AudioEffect;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.MediaRecorder;
import android.media.MicrophoneInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "BTFrontCamTest";
    private static final int REQ_PERMS = 1001;

    private TextureView preview;
    private TextView status;
    private Button recordButton;
    private Button refreshButton;
    private Button modeButton;

    private static final int[] AUDIO_SOURCES = {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED
    };
    private static final String[] AUDIO_MODE_NAMES = {
            "ROZMOWA", "MIC", "ROZPOZNAWANIE", "BEZ OBRÓBKI"
    };
    // MIC was the least noisy source in the previous four-way test.
    private int audioModeIndex = 1;
    private static final int REQUESTED_SAMPLE_RATE = 48_000;
    private static final int REQUESTED_AUDIO_BITRATE = 192_000;
    private String lastAudioDiagnostics = "";
    private static final String[] MODE_TAGS = {
            "CALL", "MIC", "RECOGNITION", "UNPROCESSED"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private String frontCameraId;
    private Size videoSize = new Size(1280, 720);
    private int sensorOrientation = 0;

    private AudioManager audioManager;
    private AudioDeviceInfo selectedBtInput;
    private AudioDeviceInfo selectedBtCommunicationDevice;

    private MediaRecorder mediaRecorder;
    private Uri outputUri;
    private ParcelFileDescriptor outputPfd;

    private boolean isRecording = false;
    private boolean routeVerified = false;
    private boolean aborting = false;
    private boolean cameraOpening = false;
    private boolean previewConfiguring = false;

    private final AudioRouting.OnRoutingChangedListener routingListener = router -> {
        if (!isRecording || aborting || mediaRecorder == null) return;
        AudioDeviceInfo routed = mediaRecorder.getRoutedDevice();
        logStatus("ROUTE CHANGED -> " + describeDevice(routed));
        if (!isBluetoothMicType(routed)) {
            abortRecording("Android zmienił wejście audio na NIE-BT: " + describeDevice(routed));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        recordButton = findViewById(R.id.record);
        refreshButton = findViewById(R.id.refresh);
        modeButton = findViewById(R.id.mode);
        modeButton.setOnClickListener(v -> {
            if (isRecording) return;
            audioModeIndex = (audioModeIndex + 1) % AUDIO_SOURCES.length;
            updateAudioModeButton();
            setStatus("Tryb audio: " + AUDIO_MODE_NAMES[audioModeIndex]
                    + "\nŹródło jest nadal wymuszane na BT. Kliknij NAGRYWAJ.");
        });
        updateAudioModeButton();

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        preview.setSurfaceTextureListener(this);

        refreshButton.setOnClickListener(v -> refreshBluetoothDevices());
        recordButton.setOnClickListener(v -> {
            if (isRecording) stopRecording(false);
            else startBtOnlyRecording();
        });

        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERMS);
        } else {
            initialize();
        }
    }

    private void updateAudioModeButton() {
        if (modeButton != null) {
            modeButton.setText("AUDIO: " + AUDIO_MODE_NAMES[audioModeIndex]
                    + " (DOTKNIJ ABY ZMIENIĆ)");
        }
    }

    private String[] requiredPermissions() {
        return new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT
        };
    }

    private boolean hasPermissions() {
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && hasPermissions()) {
            initialize();
        } else {
            setStatus("Brak wymaganych uprawnień CAMERA / RECORD_AUDIO / BLUETOOTH_CONNECT.");
        }
    }

    private void initialize() {
        try {
            findFrontCamera();
            refreshBluetoothDevices();
            if (preview.isAvailable()) openCamera();
        } catch (Exception e) {
            setStatus("INIT ERROR: " + e);
            Log.e(TAG, "init", e);
        }
    }

    private void findFrontCamera() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                frontCameraId = id;
                Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientation = so == null ? 0 : so;

                StreamConfigurationMap map =
                        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] sizes = map.getOutputSizes(MediaRecorder.class);
                    if (sizes != null && sizes.length > 0) {
                        videoSize = chooseVideoSize(sizes);
                    }
                }
                setStatus("Front camera: " + id + " / " + videoSize.getWidth() + "x" + videoSize.getHeight());
                return;
            }
        }
        throw new IllegalStateException("Nie znaleziono przedniej kamery.");
    }

    private Size chooseVideoSize(Size[] sizes) {
        // Prefer 720p on OEM Camera2 implementations; it is almost universally
        // valid for both SurfaceTexture preview and MediaRecorder.
        for (Size s : sizes) {
            if (s.getWidth() == 1280 && s.getHeight() == 720) return s;
        }
        List<Size> candidates = new ArrayList<>();
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            double ratio = s.getWidth() / (double) s.getHeight();
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080 &&
                    area >= 640L * 480L && Math.abs(ratio - (16.0 / 9.0)) < 0.08) {
                candidates.add(s);
            }
        }
        if (!candidates.isEmpty()) {
            candidates.sort(Comparator.comparingLong(
                    s -> -((long) s.getWidth() * s.getHeight())));
            return candidates.get(0);
        }

        return Arrays.stream(sizes)
                .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                .orElse(sizes[0]);
    }

    private void refreshBluetoothDevices() {
        if (!hasPermissions()) return;

        selectedBtInput = null;
        selectedBtCommunicationDevice = null;

        AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo d : inputs) {
            if (isBluetoothMicType(d)) {
                selectedBtInput = d;
                break;
            }
        }

        for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
            if (isBluetoothCommType(d)) {
                selectedBtCommunicationDevice = d;
                break;
            }
        }

        for (AudioDeviceInfo comm : audioManager.getAvailableCommunicationDevices()) {
            if (comm.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                for (AudioDeviceInfo input : inputs) {
                    if (input.isSource()
                            && input.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        selectedBtInput = input;
                        selectedBtCommunicationDevice = comm;
                        break;
                    }
                }
            }
            if (selectedBtInput != null
                    && selectedBtInput.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("BT INPUT: ").append(describeDevice(selectedBtInput)).append("\n");
        sb.append("BT COMM:  ").append(describeDevice(selectedBtCommunicationDevice)).append("\n");
        sb.append("Current COMM: ").append(describeDevice(audioManager.getCommunicationDevice())).append("\n");
        if (selectedBtInput != null) {
            sb.append("BT sample rates: ")
              .append(Arrays.toString(selectedBtInput.getSampleRates()))
              .append("\n");
        }
        sb.append("Nagrywanie: AAC 48 kHz, 192 kb/s, mono\n");
        sb.append("Front: ").append(frontCameraId == null ? "?" : frontCameraId)
                .append(" / ").append(videoSize.getWidth()).append("x").append(videoSize.getHeight());

        setStatus(sb.toString());
    }

    private boolean isBluetoothMicType(AudioDeviceInfo d) {
        if (d == null || !d.isSource()) return false;
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                || t == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private boolean isBluetoothCommType(AudioDeviceInfo d) {
        if (d == null) return false;
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                || t == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private String describeDevice(AudioDeviceInfo d) {
        if (d == null) return "BRAK";
        return String.format(Locale.US, "%s [type=%d id=%d src=%s sink=%s]",
                d.getProductName(), d.getType(), d.getId(), d.isSource(), d.isSink());
    }

    private void startBtOnlyRecording() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERMS);
            return;
        }

        refreshBluetoothDevices();

        if (selectedBtInput == null) {
            setStatus("STOP: Android nie wystawia żadnego mikrofonu BT jako INPUT.\n"
                    + "Podłącz słuchawki z aktywnym profilem rozmów i kliknij „Sprawdź BT”.");
            return;
        }

        if (selectedBtCommunicationDevice == null) {
            setStatus("STOP: słuchawki są widoczne jako wejście, ale brak urządzenia BT "
                    + "na liście communication devices.");
            return;
        }

        boolean routeRequestOk;
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            routeRequestOk = audioManager.setCommunicationDevice(selectedBtCommunicationDevice);
        } catch (Exception e) {
            setStatus("setCommunicationDevice ERROR: " + e);
            return;
        }

        if (!routeRequestOk) {
            setStatus("STOP: setCommunicationDevice(BT) zwróciło false.");
            restoreAudioRoute();
            return;
        }

        setStatus("Żądanie routingu BT przyjęte.\n"
                + "COMM = " + describeDevice(audioManager.getCommunicationDevice())
                + "\nStart nagrywania za 700 ms...");

        mainHandler.postDelayed(() -> {
            if (!isFinishing()) {
                try {
                    prepareAndStartRecorder();
                } catch (Exception e) {
                    Log.e(TAG, "record start", e);
                    cleanupFailedStart();
                    setStatus("START ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }, 700);
    }

    private void prepareAndStartRecorder() throws Exception {
        if (cameraDevice == null) throw new IllegalStateException("Kamera nie jest otwarta.");

        outputUri = createOutputUri();
        if (outputUri == null) throw new IOException("Nie udało się utworzyć pliku w MediaStore.");

        outputPfd = getContentResolver().openFileDescriptor(outputUri, "rw");
        if (outputPfd == null) throw new IOException("Nie udało się otworzyć deskryptora pliku.");

        mediaRecorder = new MediaRecorder();

        // Route BT is enforced independently; test changes only Android source processing.
        mediaRecorder.setAudioSource(AUDIO_SOURCES[audioModeIndex]);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoEncodingBitRate(8_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());

        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(REQUESTED_AUDIO_BITRATE);
        mediaRecorder.setAudioSamplingRate(REQUESTED_SAMPLE_RATE);
        mediaRecorder.setAudioChannels(1);

        mediaRecorder.setOrientationHint(calculateOrientationHint());
        mediaRecorder.setOutputFile(outputPfd.getFileDescriptor());

        boolean preferredAccepted = mediaRecorder.setPreferredDevice(selectedBtInput);
        if (!preferredAccepted) {
            throw new IllegalStateException("MediaRecorder odrzucił setPreferredDevice(BT).");
        }

        mediaRecorder.addOnRoutingChangedListener(routingListener, mainHandler);
        mediaRecorder.prepare();

        SurfaceTexture texture = preview.getSurfaceTexture();
        if (texture == null) throw new IllegalStateException("Brak SurfaceTexture.");
        texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());

        Surface previewSurface = new Surface(texture);
        Surface recorderSurface = mediaRecorder.getSurface();

        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }

        cameraDevice.createCaptureSession(
                Arrays.asList(previewSurface, recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            CaptureRequest.Builder builder =
                                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            builder.addTarget(previewSurface);
                            builder.addTarget(recorderSurface);
                            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

                            session.setRepeatingRequest(builder.build(), null, mainHandler);

                            mediaRecorder.start();
                            isRecording = true;
                            routeVerified = false;
                            recordButton.setText("STOP");
                            modeButton.setEnabled(false);

                            setStatus("AUDIO: " + AUDIO_MODE_NAMES[audioModeIndex] + "\nNagrywanie wystartowało.\n"
                                    + "Preferred: " + describeDevice(mediaRecorder.getPreferredDevice())
                                    + "\nWeryfikuję FAKTYCZNY routing...");

                            mainHandler.postDelayed(MainActivity.this::verifyActualRoute, 500);
                        } catch (Exception e) {
                            Log.e(TAG, "session start", e);
                            abortRecording("Błąd startu sesji: " + e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        abortRecording("Nie udało się skonfigurować sesji Camera2.");
                    }
                },
                mainHandler
        );
    }

    private void verifyActualRoute() {
        if (!isRecording || mediaRecorder == null || aborting) return;

        AudioDeviceInfo routed = mediaRecorder.getRoutedDevice();
        AudioDeviceInfo comm = audioManager.getCommunicationDevice();

        StringBuilder micInfo = new StringBuilder();
        try {
            List<MicrophoneInfo> active = mediaRecorder.getActiveMicrophones();
            for (MicrophoneInfo m : active) {
                if (micInfo.length() > 0) micInfo.append(", ");
                micInfo.append(m.getId());
            }
        } catch (Exception e) {
            micInfo.append("n/a");
        }

        if (!isBluetoothMicType(routed)) {
            abortRecording("TEST NEGATYWNY — Android NIE nagrywa przez BT.\n"
                    + "Routed: " + describeDevice(routed) + "\n"
                    + "COMM: " + describeDevice(comm) + "\n"
                    + "Plik został skasowany.");
            return;
        }

        routeVerified = true;
        lastAudioDiagnostics = captureAudioDiagnostics();
        setStatus("TEST OK — mikrofon BT. Tryb: " + AUDIO_MODE_NAMES[audioModeIndex] + "\n"
                + "MP4: AAC 48 kHz / 192 kb/s (zadane)\n"
                + lastAudioDiagnostics + "\n"
                + "ROUTED: " + describeDevice(routed) + "\n"
                + "COMM:   " + describeDevice(comm) + "\n"
                + "Active mic IDs: " + micInfo + "\n"
                + "Nagrywanie trwa. Plik będzie w Movies/BTFrontCamTest.");
    }

    // Android HAL format may differ from AAC format due to sample-rate conversion.
    // A 48 kHz HAL format still does not prove 48 kHz over the Bluetooth radio link.
    private String captureAudioDiagnostics() {
        StringBuilder sb = new StringBuilder();
        try {
            AudioRecordingConfiguration cfg = mediaRecorder.getActiveRecordingConfiguration();
            if (cfg == null) {
                sb.append("Android audio config: niedostepna\\n");
            } else {
                AudioFormat client = cfg.getClientFormat();
                AudioFormat device = cfg.getFormat();
                int clientHz = client == null ? 0 : client.getSampleRate();
                int deviceHz = device == null ? 0 : device.getSampleRate();
                sb.append("APP rate: ").append(clientHz).append(" Hz\\n");
                sb.append("HAL rate: ").append(deviceHz).append(" Hz\\n");
                AudioDeviceInfo captureDevice = cfg.getAudioDevice();
                sb.append("HAL input: ").append(describeDevice(captureDevice)).append("\\n");
                if (deviceHz > 0 && deviceHz < REQUESTED_SAMPLE_RATE) {
                    sb.append("UWAGA: Android przelicza nizsza czestotliwosc na 48 kHz\\n");
                } else if (deviceHz >= REQUESTED_SAMPLE_RATE) {
                    sb.append("HAL 48k; szerokosc pasma BT nadal wymaga analizy audio\\n");
                }
                List<AudioEffect.Descriptor> effects = cfg.getEffects();
                sb.append("Efekty Android: ");
                if (effects == null || effects.isEmpty()) {
                    sb.append("brak/nieujawnione");
                } else {
                    for (AudioEffect.Descriptor effect : effects) {
                        sb.append(effect.name).append(" ");
                    }
                }
                sb.append("\\n");
            }
        } catch (Exception e) {
            sb.append("Audio config error: ").append(e.getClass().getSimpleName()).append("\\n");
        }
        if (selectedBtInput != null) {
            try {
                sb.append("BT rates: ")
                  .append(Arrays.toString(selectedBtInput.getSampleRates()))
                  .append(" (pusta lista = dowolne/nieznane)\\n");
            } catch (Exception ignored) {}
        }
        String result = sb.toString().replace("\\n", "\n");
        Log.i(TAG, "48K AUDIO DIAGNOSTICS " + result.replace('\n', ' '));
        return result;
    }

    private void abortRecording(String reason) {
        if (aborting) return;
        aborting = true;
        mainHandler.post(() -> {
            stopRecording(true);
            setStatus(reason);
            aborting = false;
        });
    }

    private void stopRecording(boolean deleteFile) {
        if (captureSession != null) {
            try { captureSession.stopRepeating(); } catch (Exception ignored) {}
            try { captureSession.abortCaptures(); } catch (Exception ignored) {}
            captureSession.close();
            captureSession = null;
        }

        boolean wasRecording = isRecording;
        isRecording = false;
        routeVerified = false;
        recordButton.setText("NAGRYWAJ");
        modeButton.setEnabled(true);

        if (mediaRecorder != null) {
            try {
                mediaRecorder.removeOnRoutingChangedListener(routingListener);
            } catch (Exception ignored) {}

            if (wasRecording) {
                try {
                    mediaRecorder.stop();
                } catch (Exception e) {
                    deleteFile = true;
                    Log.w(TAG, "stop failed", e);
                }
            }

            try { mediaRecorder.reset(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }

        try {
            if (outputPfd != null) outputPfd.close();
        } catch (IOException ignored) {}
        outputPfd = null;

        if (outputUri != null) {
            if (deleteFile) {
                try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
            } else {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                try { getContentResolver().update(outputUri, done, null, null); } catch (Exception ignored) {}
            }
        }

        outputUri = null;
        restoreAudioRoute();

        if (cameraDevice != null && preview.isAvailable()) {
            mainHandler.postDelayed(this::startPreview, 250);
        }

        if (!deleteFile && wasRecording) {
            setStatus("Zapisano film w Movies/BTFrontCamTest.\n"
                    + "Tryb: " + AUDIO_MODE_NAMES[audioModeIndex] + "\n"
                    + lastAudioDiagnostics);
        }
    }

    private void cleanupFailedStart() {
        isRecording = false;
        routeVerified = false;
        recordButton.setText("NAGRYWAJ");
        modeButton.setEnabled(true);

        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        try {
            if (outputPfd != null) outputPfd.close();
        } catch (IOException ignored) {}
        outputPfd = null;

        if (outputUri != null) {
            try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
            outputUri = null;
        }
        restoreAudioRoute();
        startPreview();
    }

    private void restoreAudioRoute() {
        try { audioManager.clearCommunicationDevice(); } catch (Exception ignored) {}
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
    }

    private Uri createOutputUri() {
        String name = "BT48k_" + MODE_TAGS[audioModeIndex] + "_" + System.currentTimeMillis() + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/BTFrontCamTest");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }

    private int calculateOrientationHint() {
        int rotation = getDisplay().getRotation();
        int deviceDegrees;
        switch (rotation) {
            case Surface.ROTATION_90: deviceDegrees = 90; break;
            case Surface.ROTATION_180: deviceDegrees = 180; break;
            case Surface.ROTATION_270: deviceDegrees = 270; break;
            case Surface.ROTATION_0:
            default: deviceDegrees = 0; break;
        }
        // Przednia kamera.
        return (sensorOrientation + deviceDegrees) % 360;
    }

    private void openCamera() {
        if (!hasPermissions() || frontCameraId == null || cameraDevice != null || cameraOpening) return;
        cameraOpening = true;
        try {
            cameraManager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraOpening = false;
                    cameraDevice = camera;
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    cameraOpening = false;
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    cameraOpening = false;
                    camera.close();
                    cameraDevice = null;
                    setStatus("Camera error: " + error);
                }
            }, mainHandler);
        } catch (Exception e) {
            cameraOpening = false;
            setStatus("openCamera ERROR: " + e);
        }
    }

    private void startPreview() {
        if (cameraDevice == null || !preview.isAvailable() || isRecording || previewConfiguring) return;
        previewConfiguring = true;
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) { previewConfiguring = false; return; }
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface surface = new Surface(texture);

            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            previewConfiguring = false;
                            if (cameraDevice == null || isRecording) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(surface);
                                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                session.setRepeatingRequest(builder.build(), null, mainHandler);
                                setStatus("Preview OK: " + videoSize.getWidth() + "x" + videoSize.getHeight()
                                        + "\nKliknij SPRAWDŹ BT, potem NAGRYWAJ.");
                            } catch (CameraAccessException e) {
                                setStatus("Preview ERROR: " + e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            previewConfiguring = false;
                            session.close();
                            setStatus("Preview configure failed: " + videoSize.getWidth() + "x" + videoSize.getHeight());
                        }
                    },
                    mainHandler
            );
        } catch (Exception e) {
            previewConfiguring = false;
            setStatus("startPreview ERROR: " + e);
        }
    }

    private void setStatus(String text) {
        status.setText(text);
        Log.i(TAG, text.replace('\n', ' '));
    }

    private void logStatus(String text) {
        Log.i(TAG, text);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isRecording) stopRecording(true);
        closeCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermissions() && preview.isAvailable() && cameraDevice == null) openCamera();
    }

    private void closeCamera() {
        previewConfiguring = false;
        cameraOpening = false;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        restoreAudioRoute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        restoreAudioRoute();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (hasPermissions()) openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
}
