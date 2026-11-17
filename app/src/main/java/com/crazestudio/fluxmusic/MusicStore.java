package com.crazestudio.fluxmusic;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MusicStore {
    private static final String SEP="|";
    private final SharedPreferences prefs;
    public MusicStore(Context c){prefs=c.getSharedPreferences("flux_music_store",Context.MODE_PRIVATE);}
    private List<String> read(String key){
        String raw=prefs.getString(key,"");List<String> out=new ArrayList<>();
        if(raw!=null&&!raw.isEmpty())for(String s:raw.split("\\|",-1))if(!s.isEmpty())out.add(s);
        return out;
    }
    private void write(String key,List<String> values){prefs.edit().putString(key,String.join(SEP,values)).apply();}
    private Set<String> set(String key){return new LinkedHashSet<>(read(key));}
    public boolean isFavorite(String id){return set("favorites").contains(id);}
    public void toggleFavorite(String id){Set<String>s=set("favorites");if(!s.add(id))s.remove(id);write("favorites",new ArrayList<>(s));}
    public List<String> getFavoriteIds(){return read("favorites");}
    public void addRecent(String id){LinkedHashSet<String>s=new LinkedHashSet<>();s.add(id);s.addAll(read("recents"));List<String>v=new ArrayList<>(s);if(v.size()>30)v=new ArrayList<>(v.subList(0,30));write("recents",v);}
    public List<String> getRecentIds(){return read("recents");}
    public void addSearch(String q){if(q==null||q.trim().isEmpty())return;LinkedHashSet<String>s=new LinkedHashSet<>();s.add(q.trim());s.addAll(read("searches"));List<String>v=new ArrayList<>(s);if(v.size()>8)v=new ArrayList<>(v.subList(0,8));write("searches",v);}
    public List<String> getSearches(){return read("searches");}
    public void clearSearches(){prefs.edit().remove("searches").apply();}
    public void createPlaylist(String name){Set<String>s=set("playlist_names");s.add(name.replace("|",""));write("playlist_names",new ArrayList<>(s));}
    public List<String> getPlaylists(){return read("playlist_names");}
    public void addToPlaylist(String name,String id){String key="playlist_"+name;Set<String>s=set(key);s.add(id);write(key,new ArrayList<>(s));}
    public List<String> getPlaylistTrackIds(String name){return read("playlist_"+name);}
}
