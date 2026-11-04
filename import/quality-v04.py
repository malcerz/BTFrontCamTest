from pathlib import Path

j = Path("btfrontcam-src/app/src/main/java/com/malcerz/btfrontcamtest/MainActivity.java")
s = j.read_text(encoding="utf-8")

def patch_once(before, after, label):
    global s
    n = s.count(before)
    if n != 1:
        raise RuntimeError(f"{label}: expected 1 occurrence, got {n}")
    s = s.replace(before, after, 1)

patch_once(
    "    private Button refreshButton;\n",
    """    private Button refreshButton;
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
    private int audioModeIndex = 0;
""",
    "mode fields"
)

patch_once(
    "        refreshButton = findViewById(R.id.refresh);\n",
    """        refreshButton = findViewById(R.id.refresh);
        modeButton = findViewById(R.id.mode);
        modeButton.setOnClickListener(v -> {
            if (isRecording) return;
            audioModeIndex = (audioModeIndex + 1) % AUDIO_SOURCES.length;
            updateAudioModeButton();
            setStatus("Tryb audio: " + AUDIO_MODE_NAMES[audioModeIndex]
                    + "\\nŹródło jest nadal wymuszane na BT. Kliknij NAGRYWAJ.");
        });
        updateAudioModeButton();
""",
    "mode UI onCreate"
)

patch_once(
    "    private String[] requiredPermissions() {\n",
    """    private void updateAudioModeButton() {
        if (modeButton != null) {
            modeButton.setText("AUDIO: " + AUDIO_MODE_NAMES[audioModeIndex]
                    + " (DOTKNIJ ABY ZMIENIĆ)");
        }
    }

    private String[] requiredPermissions() {
""",
    "UI helper"
)

patch_once(
    "        // VOICE_COMMUNICATION + MODE_IN_COMMUNICATION celowo kierują Androida na tor HFP/SCO/LE.\n        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);\n",
    """        // Route BT is enforced independently; test changes only Android source processing.
        mediaRecorder.setAudioSource(AUDIO_SOURCES[audioModeIndex]);
""",
    "audio source"
)

patch_once(
    "        mediaRecorder.setAudioEncodingBitRate(64_000);\n",
    "        mediaRecorder.setAudioEncodingBitRate(128_000);\n",
    "AAC bitrate"
)

patch_once(
    "                            recordButton.setText(\"STOP\");\n",
    """                            recordButton.setText("STOP");
                            modeButton.setEnabled(false);
""",
    "disable mode on record"
)

stop_button = '        recordButton.setText("NAGRYWAJ");\n'
if s.count(stop_button) != 2:
    raise RuntimeError("expected 2 STOP/cleanup UI resets")
s = s.replace(stop_button, stop_button + '        modeButton.setEnabled(true);\n')

patch_once(
    '                            setStatus("Nagrywanie wystartowało.\\n"\n',
    '                            setStatus("AUDIO: " + AUDIO_MODE_NAMES[audioModeIndex] + "\\nNagrywanie wystartowało.\\n"\n',
    "mode in recording status"
)

patch_once(
    '        setStatus("TEST OK — faktyczne wejście jest BT.\\n"\n',
    '        setStatus("TEST OK — mikrofon BT. Tryb: " + AUDIO_MODE_NAMES[audioModeIndex] + "\\n"\n',
    "mode in verified status"
)

j.write_text(s, encoding="utf-8")

layout_file = Path("btfrontcam-src/app/src/main/res/layout/activity_main.xml")
layout = layout_file.read_text(encoding="utf-8")
needle = """    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="8dp">"""
assert layout.count(needle) == 1, "layout insertion"
layout = layout.replace(needle, """    <Button
        android:id="@+id/mode"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginLeft="8dp"
        android:layout_marginRight="8dp"
        android:text="AUDIO: ROZMOWA (DOTKNIJ ABY ZMIENIĆ)" />

""" + needle, 1)
layout_file.write_text(layout, encoding="utf-8")

bp = Path("btfrontcam-src/app/build.gradle")
b = bp.read_text(encoding="utf-8")
assert 'versionCode 3' in b
b = b.replace('versionCode 3','versionCode 4').replace('versionName "0.3-test"','versionName "0.4-test"')
bp.write_text(b, encoding="utf-8")

print("v0.4 audio comparison UI patch applied")
