package com.crazestudio.fluxmusic;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * YouTube Music catalogue adapter. Network identity is centralized so the
 * InnerTube and stream extractor requests use the same explicit Android UA.
 */
public final class YtMusicCatalog {
    public static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TECNO KI5k) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    private static final String SEARCH_ENDPOINT =
        "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String CLIENT_VERSION = "1.20261116.01.00";
    private static volatile boolean extractorReady;
    private static final long CACHE_TTL_MS=600000L;
    private static final LinkedHashMap<String,CacheEntry> SEARCH_CACHE=new LinkedHashMap<String,CacheEntry>(32,0.75f,true);
    private static final class CacheEntry { final long time=System.currentTimeMillis(); final List<Track> tracks; CacheEntry(List<Track> t){tracks=new ArrayList<>(t);} }
    private YtMusicCatalog() {}

    public static List<Track> search(String query) throws Exception {
        String normalized=query==null?"":query.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        if(normalized.isEmpty())return Collections.emptyList();
        synchronized(SEARCH_CACHE){CacheEntry e=SEARCH_CACHE.get(normalized);if(e!=null&&System.currentTimeMillis()-e.time<CACHE_TTL_MS)return new ArrayList<>(e.tracks);if(e!=null)SEARCH_CACHE.remove(normalized);}
        JSONObject client = new JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "IN");
        JSONObject body = new JSONObject()
            .put("context", new JSONObject().put("client", client))
            .put("query", query);
        HttpURLConnection c = (HttpURLConnection) new URL(SEARCH_ENDPOINT).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Origin", "https://music.youtube.com");
        c.setRequestProperty("Referer", "https://music.youtube.com/");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        String json = read(in);
        c.disconnect();
        if (status < 200 || status >= 300)
            throw new IOException("YouTube Music search HTTP " + status);
        LinkedHashMap<String, Track> tracks = new LinkedHashMap<>();
        collect(new JSONObject(json), tracks);
        List<Track> result=new ArrayList<>(tracks.values());
        synchronized(SEARCH_CACHE){SEARCH_CACHE.put(normalized,new CacheEntry(result));while(SEARCH_CACHE.size()>80)SEARCH_CACHE.remove(SEARCH_CACHE.keySet().iterator().next());}
        return result;
    }

    private static void collect(Object node, Map<String, Track> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject renderer = o.optJSONObject("musicResponsiveListItemRenderer");
            if (renderer != null) {
                Track t = parse(renderer);
                if (t != null) out.put(t.id, t);
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) collect(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
        }
    }

    private static Track parse(JSONObject r) {
        String id = deepString(r, "videoId");
        if (id.isEmpty()) return null;
        JSONArray flex = r.optJSONArray("flexColumns");
        String title = flex != null && flex.length() > 0 ? text(flex.optJSONObject(0)) : "";
        String artist = flex != null && flex.length() > 1 ? text(flex.optJSONObject(1)) : "";
        String album = flex != null && flex.length() > 2 ? text(flex.optJSONObject(2)) : "";
        JSONArray fixed = r.optJSONArray("fixedColumns");
        String duration = fixed != null && fixed.length() > 0 ? text(fixed.optJSONObject(0)) : "";
        return new Track(id, title, artist, album, durationMillis(duration), thumbnail(r));
    }

    private static String deepString(Object n, String key) {
        if (n instanceof JSONObject) {
            JSONObject o = (JSONObject)n;
            if (o.opt(key) instanceof String) return o.optString(key);
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String v = deepString(o.opt(it.next()), key); if (!v.isEmpty()) return v; }
        } else if (n instanceof JSONArray) {
            JSONArray a = (JSONArray)n;
            for (int i=0;i<a.length();i++) { String v=deepString(a.opt(i),key); if(!v.isEmpty()) return v; }
        }
        return "";
    }

    private static String text(Object n) {
        if (n instanceof JSONObject) {
            JSONObject o=(JSONObject)n;
            if (o.opt("text") instanceof String) return o.optString("text");
            JSONArray runs=o.optJSONArray("runs");
            if(runs!=null){StringBuilder b=new StringBuilder();for(int i=0;i<runs.length();i++){JSONObject x=runs.optJSONObject(i);if(x!=null)b.append(x.optString("text",""));}if(b.length()>0)return b.toString();}
            Iterator<String> it=o.keys();while(it.hasNext()){String v=text(o.opt(it.next()));if(!v.isEmpty())return v;}
        } else if(n instanceof JSONArray){JSONArray a=(JSONArray)n;for(int i=0;i<a.length();i++){String v=text(a.opt(i));if(!v.isEmpty())return v;}}
        return "";
    }

    private static String thumbnail(Object n) {
        if(n instanceof JSONObject){JSONObject o=(JSONObject)n;JSONObject t=o.optJSONObject("thumbnail");if(t!=null){JSONArray a=t.optJSONArray("thumbnails");if(a!=null&&a.length()>0){JSONObject last=a.optJSONObject(a.length()-1);if(last!=null)return last.optString("url","");}}Iterator<String>it=o.keys();while(it.hasNext()){String v=thumbnail(o.opt(it.next()));if(!v.isEmpty())return v;}}
        else if(n instanceof JSONArray){JSONArray a=(JSONArray)n;for(int i=0;i<a.length();i++){String v=thumbnail(a.opt(i));if(!v.isEmpty())return v;}}
        return "";
    }

    private static long durationMillis(String value) {
        try { long n=0; for(String part:value.trim().split(":")) n=n*60+Long.parseLong(part); return n*1000; }
        catch(Exception ignored){return 0;}
    }

    public static synchronized void initializeExtractor() {
        if (extractorReady) return;
        NewPipe.init(new FluxDownloader());
        extractorReady = true;
    }

    public static String resolveAudioUrl(String videoId) throws Exception {
        initializeExtractor();
        StreamingService service = NewPipe.getService("YouTube");
        StreamExtractor extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=" + videoId);
        extractor.fetchPage();
        List<AudioStream> streams = extractor.getAudioStreams();
        if (streams == null || streams.isEmpty()) throw new IOException("No audio-only stream available for this video.");
        streams.sort((a,b) -> Integer.compare(b.getAverageBitrate(), a.getAverageBitrate()));
        return streams.get(0).getContent();
    }

    private static String read(InputStream in) throws IOException {
        if(in==null)return "";
        try(InputStream src=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;while((n=src.read(buf))!=-1)out.write(buf,0,n);
            return out.toString("UTF-8");
        }
    }

    private static final class FluxDownloader extends Downloader {
        @Override public Response execute(Request request) throws IOException {
            HttpURLConnection c=(HttpURLConnection)new URL(request.url()).openConnection();
            c.setRequestMethod(request.httpMethod());
            c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",USER_AGENT);
            Map<String,List<String>> headers=request.headers();
            if(headers!=null)for(Map.Entry<String,List<String>>e:headers.entrySet())if(e.getValue()!=null)for(String v:e.getValue())c.addRequestProperty(e.getKey(),v);
            byte[] data=request.dataToSend();
            if(data!=null){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(data);}}
            int status=c.getResponseCode();
            InputStream in=status>=200&&status<400?c.getInputStream():c.getErrorStream();
            String body=read(in);
            Map<String,List<String>> responseHeaders=new HashMap<>();
            for(Map.Entry<String,List<String>>e:c.getHeaderFields().entrySet())if(e.getKey()!=null)responseHeaders.put(e.getKey(),e.getValue());
            Response response=new Response(status,c.getResponseMessage(),responseHeaders,body,c.getURL().toString());
            c.disconnect();return response;
        }
    }
}
