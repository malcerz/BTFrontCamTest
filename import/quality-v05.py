from pathlib import Path

j = Path("btfrontcam-src/app/src/main/java/com/malcerz/btfrontcamtest/MainActivity.java")
s = j.read_text(encoding="utf-8")

def once(before, after, name):
    global s
    n = s.count(before)
    if n != 1:
        raise RuntimeError(f"{name}: expected 1 anchor, found {n}")
    s = s.replace(before, after, 1)

once("import android.media.AudioDeviceInfo;\n",
     "import android.media.AudioDeviceInfo;\nimport android.media.AudioRecordingConfiguration;\nimport android.media.AudioFormat;\nimport android.media.audiofx.AudioEffect;\n",
     "imports")

once("    private int audioModeIndex = 0;\n",
     """    // MIC was the least noisy source in the previous four-way test.
    private int audioModeIndex = 1;
    private static final int REQUESTED_SAMPLE_RATE = 48_000;
    private static final int REQUESTED_AUDIO_BITRATE = 192_000;
    private String lastAudioDiagnostics = "";
    private static final String[] MODE_TAGS = {
            "CALL", "MIC", "RECOGNITION", "UNPROCESSED"
    };
""", "default audio source")

once("        mediaRecorder.setAudioEncodingBitRate(128_000);\n",
     "        mediaRecorder.setAudioEncodingBitRate(REQUESTED_AUDIO_BITRATE);\n",
     "audio bitrate")

once("        mediaRecorder.setAudioSamplingRate(16_000);\n",
     "        mediaRecorder.setAudioSamplingRate(REQUESTED_SAMPLE_RATE);\n",
     "requested sample rate")

once('        String name = "BTFrontCamTest_" + System.currentTimeMillis() + ".mp4";\n',
     '        String name = "BT48k_" + MODE_TAGS[audioModeIndex] + "_" + System.currentTimeMillis() + ".mp4";\n',
     "filename with audio mode")

once('        routeVerified = true;\n        setStatus("TEST OK — mikrofon BT. Tryb: " + AUDIO_MODE_NAMES[audioModeIndex] + "\\n"\n',
     '''        routeVerified = true;
        lastAudioDiagnostics = captureAudioDiagnostics();
        setStatus("TEST OK — mikrofon BT. Tryb: " + AUDIO_MODE_NAMES[audioModeIndex] + "\\n"
                + "MP4: AAC 48 kHz / 192 kb/s (zadane)\\n"
                + lastAudioDiagnostics + "\\n"
''', "diagnostics status")

once("    private void abortRecording(String reason) {\n",
     r'''    // Android HAL format may differ from AAC format due to sample-rate conversion.
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
''', "diagnostic method")

# Keep the legacy fallback intact; prefer real Bluetooth LE input when both
# communication endpoint and source are exposed by the system.
once("        StringBuilder sb = new StringBuilder();\n        sb.append(\"BT INPUT: \")",
     '''        for (AudioDeviceInfo comm : audioManager.getAvailableCommunicationDevices()) {
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
        sb.append("BT INPUT: ")''', "BLE priority")

once('        sb.append("Current COMM: ").append(describeDevice(audioManager.getCommunicationDevice())).append("\\n");\n',
     '''        sb.append("Current COMM: ").append(describeDevice(audioManager.getCommunicationDevice())).append("\\n");
        if (selectedBtInput != null) {
            sb.append("BT sample rates: ")
              .append(Arrays.toString(selectedBtInput.getSampleRates()))
              .append("\\n");
        }
        sb.append("Nagrywanie: AAC 48 kHz, 192 kb/s, mono\\n");
''', "display requested rate")

once('            setStatus("Zapisano film w Movies/BTFrontCamTest.");\n',
     '''            setStatus("Zapisano film w Movies/BTFrontCamTest.\\n"
                    + "Tryb: " + AUDIO_MODE_NAMES[audioModeIndex] + "\\n"
                    + lastAudioDiagnostics);
''', "save diagnostic summary")

j.write_text(s, encoding="utf-8")

bp = Path("btfrontcam-src/app/build.gradle")
b = bp.read_text(encoding="utf-8")
assert 'versionCode 4' in b, "unexpected previous version"
b = b.replace('versionCode 4', 'versionCode 5')
b = b.replace('versionName "0.4-test"', 'versionName "0.5-48k"')
# Use a separate application ID: the v0.4 APK remains installed for A/B tests.
assert 'applicationId "com.malcerz.btfrontcamtest"' in b
b = b.replace('applicationId "com.malcerz.btfrontcamtest"',
              'applicationId "com.malcerz.btfrontcamtest.hq"')
bp.write_text(b, encoding="utf-8")

manifest = Path("btfrontcam-src/app/src/main/AndroidManifest.xml")
m = manifest.read_text(encoding="utf-8")
assert 'android:label="BT FrontCam Test"' in m
m = m.replace('android:label="BT FrontCam Test"', 'android:label="BT FrontCam 48k"')
m = m.replace('android:name=".MainActivity"',
              'android:name="com.malcerz.btfrontcamtest.MainActivity"')
manifest.write_text(m, encoding="utf-8")
print("BTFrontCamTest v0.5 48k installed (independent package)")
