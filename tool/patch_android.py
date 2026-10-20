from pathlib import Path
import re

manifest_path = Path("android/app/src/main/AndroidManifest.xml")
if not manifest_path.exists():
    raise SystemExit("AndroidManifest.xml was not generated. Run flutter create . --platforms=android first.")

text = manifest_path.read_text(encoding="utf-8")

# Use a friendly app name and the custom launcher resource in the generated manifest.
app_match = re.search(r'<application\\b[^>]*>', text, re.S)
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
    main_activity.write_text(source, encoding="utf-8")

print("Patched Android label/icon, internet access, notification permission, and audio_service media playback.")
