from pathlib import Path
import xml.etree.ElementTree as ET

manifest = Path("android/app/src/main/AndroidManifest.xml")
if not manifest.exists():
    raise SystemExit("Flutter Android manifest was not generated")
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
ns = "{http://schemas.android.com/apk/res/android}"
tree = ET.parse(manifest)
root = tree.getroot()
app = root.find("application")
if app is None:
    raise SystemExit("Manifest has no application element")
permissions = [
    "android.permission.INTERNET",
    "android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.POST_NOTIFICATIONS",
]
for name in permissions:
    if not any(p.get(ns+"name") == name for p in root.findall("uses-permission")):
        ET.SubElement(root, "uses-permission", {ns+"name": name})
service_name = "com.ryanheise.audioservice.AudioService"
if not any(s.get(ns+"name") == service_name for s in app.findall("service")):
    service = ET.SubElement(app, "service", {ns+"name":service_name, ns+"foregroundServiceType":"mediaPlayback", ns+"exported":"true"})
    intent = ET.SubElement(service, "intent-filter")
    ET.SubElement(intent, "action", {ns+"name":"android.media.browse.MediaBrowserService"})
receiver_name = "com.ryanheise.audioservice.MediaButtonReceiver"
if not any(r.get(ns+"name") == receiver_name for r in app.findall("receiver")):
    receiver = ET.SubElement(app, "receiver", {ns+"name":receiver_name, ns+"exported":"true"})
    intent = ET.SubElement(receiver, "intent-filter")
    ET.SubElement(intent, "action", {ns+"name":"android.intent.action.MEDIA_BUTTON"})
tree.write(manifest, encoding="utf-8", xml_declaration=True)
