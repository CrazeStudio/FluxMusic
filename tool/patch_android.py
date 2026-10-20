from pathlib import Path
import re

manifest_path = Path("android/app/src/main/AndroidManifest.xml")
if not manifest_path.exists():
    raise SystemExit("AndroidManifest.xml was not generated. Run flutter create . --platforms=android first.")

text = manifest_path.read_text(encoding="utf-8")

# Use a friendly app name and the custom launcher resource in the generated manifest.
app_match = re.search(r'<application[^>]*>', text, re.S)
if app_match:
    app_tag = app_match.group(0)
    if re.search(r'android:label="[^"]*"', app_tag):
        app_tag = re.sub(r'android:label="[^"]*"', 'android:label="FluxMusic"', app_tag, count=1)
    else:
        app_tag = app_tag[:-1] + ' android:label="FluxMusic">'
    if re.search(r'android:icon="[^"]*"', app_tag):
        app_tag = re.sub(r'android:icon="[^"]*"', 'android:icon="@mipmap/ic_launcher"', app_tag, count=1)
    else:
        app_tag = app_tag[:-1] + ' android:icon="@mipmap/ic_launcher">'
    text = text[:app_match.start()] + app_tag + text[app_match.end():]

if 'xmlns:tools=' not in text:
    text = re.sub(
        r'<manifest\s+',
        '<manifest xmlns:tools="http://schemas.android.com/tools" ',
        text,
        count=1,
    )

permissions = [
    'android.permission.INTERNET',
    'android.permission.WAKE_LOCK',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
    'android.permission.POST_NOTIFICATIONS',
]
for permission in permissions:
    marker = f'android:name="{permission}"'
    if marker not in text:
        line = f'    <uses-permission {marker} />\n'
        text = re.sub(r'(\s*<application\b)', '\n' + line + r'\1', text, count=1)

text = re.sub(
    r'android:name="io\.flutter\.embedding\.android\.FlutterActivity"',
    'android:name="com.ryanheise.audioservice.AudioServiceActivity"',
    text,
)

service_block = '''
        <service
            android:name="com.ryanheise.audioservice.AudioService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="true"
            tools:ignore="Instantiatable">
            <intent-filter>
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>
        <receiver
            android:name="com.ryanheise.audioservice.MediaButtonReceiver"
            android:exported="true"
            tools:ignore="Instantiatable">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON" />
            </intent-filter>
        </receiver>
'''
if not re.search(r'<service\b[^>]*android:name="com\.ryanheise\.audioservice\.AudioService"', text, re.S):
    text = text.replace('</application>', service_block + '\n    </application>', 1)

manifest_path.write_text(text, encoding="utf-8")

for main_activity in Path("android/app/src/main").rglob("MainActivity.kt"):
    source = main_activity.read_text(encoding="utf-8")
    source = source.replace(
        'import io.flutter.embedding.android.FlutterActivity',
        'import com.ryanheise.audioservice.AudioServiceActivity',
    )
    source = source.replace('MainActivity : FlutterActivity()', 'MainActivity : AudioServiceActivity()')
    if 'dev.crazestudio.fluxmusic/audio_effects' not in source:
        source = source.replace(
            'import com.ryanheise.audioservice.AudioServiceActivity',
            'import com.ryanheise.audioservice.AudioServiceActivity\nimport android.media.audiofx.DynamicsProcessing\nimport android.media.audiofx.LoudnessEnhancer\nimport android.media.audiofx.BassBoost\nimport io.flutter.embedding.engine.FlutterEngine\nimport io.flutter.plugin.common.MethodChannel',
        )
        source = source.replace(
            'class MainActivity : AudioServiceActivity()',
            '''class MainActivity : AudioServiceActivity() {
    private val effectChannel = "dev.crazestudio.fluxmusic/audio_effects"
    private var effectSessionId = 0
    private var normalizationEnabled = true
    private var limiterEnabled = true
    private var drcEnabled = false
    private var bassBoostEnabled = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, effectChannel).setMethodCallHandler { call, result ->
            when (call.method) {
                "getSettings" -> result.success(mapOf("normalization" to normalizationEnabled, "limiter" to limiterEnabled, "drc" to drcEnabled, "bassBoost" to bassBoostEnabled))
                "attachSession" -> {
                    effectSessionId = call.argument<Int>("sessionId") ?: 0
                    applyAudioEffects()
                    result.success(null)
                }
                "setSettings" -> {
                    normalizationEnabled = call.argument<Boolean>("normalization") ?: true
                    limiterEnabled = call.argument<Boolean>("limiter") ?: true
                    drcEnabled = call.argument<Boolean>("drc") ?: false
                    bassBoostEnabled = call.argument<Boolean>("bassBoost") ?: false
                    applyAudioEffects()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun applyAudioEffects() {
        if (effectSessionId <= 0) return
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = if (normalizationEnabled) LoudnessEnhancer(effectSessionId).apply {
                setTargetGain(0)
                enabled = true
            } else null

            bassBoost?.release()
            bassBoost = if (bassBoostEnabled) BassBoost(0, effectSessionId).apply {
                setStrength(650.toShort())
                enabled = true
            } else null

            dynamicsProcessing?.release()
            dynamicsProcessing = null
            if (limiterEnabled || drcEnabled) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val builder = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                        2,
                        false, 0,
                        drcEnabled, if (drcEnabled) 1 else 0,
                        false, 0,
                        limiterEnabled
                    )
                    if (limiterEnabled) {
                        builder.setLimiterAllChannelsTo(
                            DynamicsProcessing.Limiter(true, true, 0, 2.0f, 80.0f, 20.0f, -1.0f, 0.0f)
                        )
                    }
                    if (drcEnabled) {
                        builder.setMbcAllChannelsTo(
                            DynamicsProcessing.Mbc(true, true, 1).apply {
                                setBand(
                                    0,
                                    DynamicsProcessing.MbcBand(true, 20000.0f, 15.0f, 150.0f, 3.0f, -18.0f, 6.0f, -60.0f, 1.0f, -60.0f, 0.0f)
                                )
                            }
                        )
                    }
                    val config = builder.build()
                    dynamicsProcessing = DynamicsProcessing(0, effectSessionId, config).apply { enabled = true }
                }
            }
        } catch (_: Exception) {
            // Audio effects are best-effort; playback must continue if a device rejects them.
        }
    }

    override fun onDestroy() {
        loudnessEnhancer?.release()
        bassBoost?.release()
        dynamicsProcessing?.release()
        super.onDestroy()
    }
}''',
        )
    main_activity.write_text(source, encoding="utf-8")

print("Patched Android label/icon, internet access, notification permission, and audio_service media playback.")


# Keep the Kotlin coroutine types referenced by JNI in release builds.
proguard_path = Path("android/app/proguard-rules.pro")
proguard_path.write_text("""-keep class kotlin.Unit { *; }
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Failure { *; }
-keep class kotlin.coroutines.Continuation { *; }
-keep class kotlin.coroutines.intrinsics.CoroutineSingletons { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }
""", encoding="utf-8")
print("Added R8 keep rules for Kotlin coroutine JNI symbols.")


# Vector icons used by the expanded playback notification actions.
drawable_dir = Path("android/app/src/main/res/drawable")
drawable_dir.mkdir(parents=True, exist_ok=True)
(drawable_dir / "ic_action_shuffle.xml").write_text("""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M17,3h4v4h-2V6.41l-4.29,4.3 -1.42,-1.42L17.59,5H17V3zM3,7h4.5c1.1,0 2.14,0.48 2.86,1.31l6.28,7.38c0.34,0.4 0.85,0.63 1.36,0.63H21v2h-3c-1.1,0 -2.14,-0.48 -2.86,-1.31L8.86,9.63C8.52,9.23 8.01,9 7.5,9H3V7zM17,17.59l-2.29,-2.3 -1.42,1.42L17,20.41V21h2v-4h-2v0.59zM3,15h4.5c0.5,0 0.98,-0.22 1.32,-0.6l1.4,-1.65 -1.42,-1.42 -1.5,1.67H3v2z"/>
</vector>
""", encoding="utf-8")
(drawable_dir / "ic_action_repeat.xml").write_text("""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M7,7h11v3l4,-4 -4,-4v3H7C4.79,5 3,6.79 3,9v2h2V9c0,-1.1 0.9,-2 2,-2zM17,17H6v-3l-4,4 4,4v-3h11c2.21,0 4,-1.79 4,-4v-2h-2v2c0,1.1 -0.9,2 -2,2z"/>
</vector>
""", encoding="utf-8")
print("Added shuffle and repeat icons for notification controls.")
