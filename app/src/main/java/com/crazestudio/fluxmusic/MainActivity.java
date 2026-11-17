package com.crazestudio.fluxmusic;

import android.Manifest;
import android.net.Uri;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity implements Player.Listener {
    static final int BG=0xFFFAF8F5, SURFACE=0xFFFFFFFF, ALT=0xFFF0ECE7, INK=0xFF151515, MUTED=0xFF77716B, LINE=0xFFE8E2DC, DARK=0xFF252525;
    final List<Track> tracks=new ArrayList<>();
    final Handler handler=new Handler(Looper.getMainLooper());
    final ExecutorService musicExecutor=Executors.newFixedThreadPool(2);
    final ExecutorService playbackExecutor=Executors.newSingleThreadExecutor();
    int searchGeneration=0;
    boolean discoveryLoading=false, discoveryLoaded=false;
    Runnable pendingSearch;
    MusicStore store;
    MediaController player;
    ListenableFuture<MediaController> controllerFuture;
    FrameLayout content;
    LinearLayout mini, nav;
    TextView miniName, miniArtist, miniToggle, playToggle, elapsed, total;
    SeekBar seek;
    String tab="home", screen="home", previousTab="home", playlist="Liked Songs", query="", language="All";
    Track active;
    final Runnable ticker=new Runnable(){public void run(){syncProgress();handler.postDelayed(this,700);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        store=new MusicStore(this);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);
        content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        mini=new LinearLayout(this);mini.setGravity(Gravity.CENTER_VERTICAL);mini.setPadding(d(10),d(7),d(12),d(7));mini.setBackground(box(SURFACE,18,LINE));
        TextView art=txt("♫",22,true,Color.WHITE);art.setGravity(Gravity.CENTER);art.setBackground(gradient(new int[]{0xFFB78D7C,0xFF596B70},12));mini.addView(art,new LinearLayout.LayoutParams(d(44),d(44)));
        LinearLayout meta=new LinearLayout(this);meta.setOrientation(1);meta.setPadding(d(10),0,d(8),0);
        miniName=txt("Choose something to play",13,true,INK);miniArtist=txt("Music for every moment",11,false,MUTED);meta.addView(miniName);meta.addView(miniArtist);mini.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
        miniToggle=txt("▶",20,true,INK);mini.addView(miniToggle);
        mini.setOnClickListener(v->{if(current()!=null){screen="player";render();}});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,d(60));mp.setMargins(d(12),d(5),d(12),d(7));root.addView(mini,mp);
        nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);nav.setPadding(d(5),d(3),d(5),d(5));root.addView(nav,new LinearLayout.LayoutParams(-1,d(62)));
        setContentView(root);drawNav();connect();render();loadDiscovery();
    }

    int d(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    TextView txt(String s,int size,boolean bold,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    GradientDrawable box(int color,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(d(r));if(stroke!=Color.TRANSPARENT)g.setStroke(d(1),stroke);return g;}
    GradientDrawable gradient(int[] colors,int r){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);g.setCornerRadius(d(r));return g;}
    void space(LinearLayout p,int h){p.addView(new View(this),new LinearLayout.LayoutParams(1,d(h)));}
    LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(d(22),d(18),d(22),d(28));l.setBackgroundColor(BG);return l;}
    View scroll(LinearLayout l){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(l);return s;}
    void connect(){
        SessionToken token=new SessionToken(this,new ComponentName(this,MusicService.class));
        controllerFuture=new MediaController.Builder(this,token).buildAsync();
        controllerFuture.addListener(()->{try{player=controllerFuture.get();player.addListener(this);syncMini();if(screen.equals("player"))render();}catch(ExecutionException|InterruptedException e){Toast.makeText(this,"Player connection failed",Toast.LENGTH_LONG).show();}},ContextCompat.getMainExecutor(this));
    }
    boolean hasAudio(){String p=Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_EXTERNAL_STORAGE;return ContextCompat.checkSelfPermission(this,p)==PackageManager.PERMISSION_GRANTED;}
    void requestAudio(){List<String> p=new ArrayList<>();p.add(Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_EXTERNAL_STORAGE);if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);requestPermissions(p.toArray(new String[0]),41);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==41){if(hasAudio())scan();else{render();Toast.makeText(this,"Allow audio access to scan your device.",Toast.LENGTH_LONG).show();}}}
    void scan(){discoveryLoaded=false;loadDiscovery();}
    void loadDiscovery(){
        if(discoveryLoading||discoveryLoaded)return;
        discoveryLoading=true;
        musicExecutor.execute(()->{
            try{
                List<Track> found=YtMusicCatalog.search("popular Hindi songs");
                runOnUiThread(()->{mergeTracks(found);discoveryLoaded=true;discoveryLoading=false;if(screen.equals("home"))render();});
            }catch(Exception e){
                runOnUiThread(()->{discoveryLoading=false;if(screen.equals("home")){render();Toast.makeText(this,"YouTube Music is unavailable. Check your connection and retry.",Toast.LENGTH_LONG).show();}});
            }
        });
    }
    void mergeTracks(List<Track> found){
        LinkedHashMap<String,Track> merged=new LinkedHashMap<>();
        for(Track t:tracks)merged.put(t.id,t);
        for(Track t:found)merged.put(t.id,t);
        tracks.clear();tracks.addAll(merged.values());
    }
    MediaItem current(){return player==null?null:player.getCurrentMediaItem();}
    Track active(){MediaItem m=current();if(m!=null){for(Track t:tracks)if(t.id.equals(m.mediaId))return t;}return active;}
    void render(){
        content.removeAllViews();boolean full=screen.equals("player");nav.setVisibility(full?View.GONE:View.VISIBLE);mini.setVisibility(!full&&current()!=null?View.VISIBLE:View.GONE);
        View v;switch(screen){case "search":v=searchPage();break;case "library":v=libraryPage();break;case "playlists":v=playlistsPage();break;case "detail":v=detailPage();break;case "settings":v=settingsPage();break;case "player":v=playerPage();break;default:v=homePage();}
        content.addView(v,new FrameLayout.LayoutParams(-1,-1));drawNav();syncMini();
    }
    void drawNav(){
        nav.removeAllViews();String[][] items={{"⌂","Home","home"},{"⌕","Search","search"},{"▤","Library","library"},{"☷","Playlists","playlists"},{"⚙","Settings","settings"}};
        for(String[] a:items){LinearLayout cell=new LinearLayout(this);cell.setOrientation(1);cell.setGravity(Gravity.CENTER);boolean on=a[2].equals(tab);cell.addView(txt(a[0],21,true,on?INK:MUTED));cell.addView(txt(a[1],10,on,on?INK:MUTED));if(on)cell.setBackground(box(ALT,13,Color.TRANSPARENT));nav.addView(cell,new LinearLayout.LayoutParams(0,d(54),1));cell.setOnClickListener(v->go(a[2]));}
    }
    void go(String t){tab=t;previousTab=t;screen=t;render();}
    void header(LinearLayout p,String title,String action,Runnable click){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.addView(txt(title,18,true,INK),new LinearLayout.LayoutParams(0,-2,1));if(action!=null){TextView a=txt(action,12,false,MUTED);r.addView(a);if(click!=null)a.setOnClickListener(v->click.run());}LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,-2);q.topMargin=d(20);q.bottomMargin=d(9);p.addView(r,q);}
    View homePage(){
        LinearLayout p=col();LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(txt("♫  FluxMusic",24,true,INK),new LinearLayout.LayoutParams(0,-2,1));top.addView(actionIcon("♧",()->Toast.makeText(this,"Notifications",0).show()));top.addView(actionIcon("◉",()->go("settings")));p.addView(top);
        space(p,20);int h=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);p.addView(txt(h<12?"Good morning,":h<17?"Good afternoon,":"Good evening,",29,true,INK));p.addView(txt("What do you want to listen to today?",15,false,MUTED));space(p,16);
        LinearLayout search=searchBox("Search songs, artists, albums...");p.addView(search);search.setOnClickListener(v->go("search"));space(p,15);p.addView(languageChips());space(p,18);
        LinearLayout banner=new LinearLayout(this);banner.setOrientation(1);banner.setGravity(Gravity.BOTTOM);banner.setPadding(d(19),d(17),d(19),d(17));banner.setBackground(gradient(new int[]{0xFFCE9D86,0xFF796C67,0xFF242B2B},22));banner.addView(txt("F E A T U R E D   P L A Y L I S T",10,true,Color.WHITE));space(banner,5);banner.addView(txt("Chill Indian Vibes",23,true,Color.WHITE));banner.addView(txt("Hindi  ·  Indie  ·  Lo-Fi",13,false,Color.WHITE));p.addView(banner,new LinearLayout.LayoutParams(-1,d(174)));banner.setOnClickListener(v->searchFor("chill"));
        header(p,"Recently Played","View all",()->go("library"));horizontalTracks(p,recent());
        header(p,"Made for your mood","Explore",()->go("search"));LinearLayout moods=new LinearLayout(this);String[] moodsText={"♫\nIndie Picks","◉\nLo-Fi Focus","☾\nNight Drive"};for(String s:moodsText){TextView c=txt(s,13,true,INK);c.setGravity(Gravity.CENTER);c.setBackground(box(ALT,16,LINE));LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,d(76),1);q.setMargins(d(3),0,d(3),0);moods.addView(c,q);c.setOnClickListener(v->searchFor(s.replace("\n"," ").toLowerCase(Locale.ROOT)));}p.addView(moods);
        if(tracks.isEmpty()){space(p,15);p.addView(empty("Loading your music","Connecting to YouTube Music…"));}
        else{header(p,"Your music","See all",()->go("library"));rows(p,filterLanguage(tracks),5);}
        return scroll(p);
    }
    LinearLayout searchBox(String hint){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(d(14),0,d(14),0);r.setBackground(box(ALT,16,Color.TRANSPARENT));r.addView(txt("⌕",24,false,INK));TextView t=txt(hint,14,false,MUTED);LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,-2,1);q.leftMargin=d(10);r.addView(t,q);r.setLayoutParams(new LinearLayout.LayoutParams(-1,d(50)));return r;}
    TextView actionIcon(String s,Runnable click){TextView t=txt(s,23,false,INK);t.setGravity(Gravity.CENTER);t.setLayoutParams(new LinearLayout.LayoutParams(d(42),d(42)));t.setOnClickListener(v->click.run());return t;}
    View languageChips(){HorizontalScrollView h=new HorizontalScrollView(this);h.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(this);for(String s:new String[]{"All","Hindi","Bengali","English","Tamil","Punjabi"}){boolean on=s.equals(language);TextView c=txt(s,13,on,on?Color.WHITE:INK);c.setGravity(Gravity.CENTER);c.setPadding(d(15),0,d(15),0);c.setBackground(box(on?DARK:ALT,22,Color.TRANSPARENT));LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-2,d(36));q.rightMargin=d(7);row.addView(c,q);c.setOnClickListener(v->{language=s;render();});}h.addView(row);return h;}
    List<Track> filterLanguage(List<Track> input){if(language.equals("All"))return input;List<Track> out=new ArrayList<>();for(Track t:input)if((t.title+" "+t.artist+" "+t.album).toLowerCase(Locale.ROOT).contains(language.toLowerCase(Locale.ROOT)))out.add(t);return out;}
    View searchPage(){
        LinearLayout p=col();p.addView(txt("Search",28,true,INK));space(p,14);EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(14);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setHint("Search songs, artists, albums...");input.setPadding(d(15),0,d(15),0);input.setBackground(box(ALT,16,Color.TRANSPARENT));input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);if(!query.isEmpty())input.setText(query);p.addView(input,new LinearLayout.LayoutParams(-1,d(50)));
        LinearLayout results=new LinearLayout(this);results.setOrientation(1);p.addView(results);input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();searchResults(results,query);}public void afterTextChanged(android.text.Editable e){}});
        input.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH||id==EditorInfo.IME_ACTION_DONE){store.addSearch(query);((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);return true;}return false;});
        searchResults(results,query);return scroll(p);
    }
    void searchResults(LinearLayout target,String text){
        if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);
        int generation=++searchGeneration;
        target.removeAllViews();
        String q=text.trim();
        if(q.isEmpty()){
            header(target,"Recent Searches","Clear",()->{store.clearSearches();searchResults(target,"");});
            for(String recent:store.getSearches()){
                TextView r=txt("◷   "+recent,14,false,INK);r.setPadding(0,d(10),0,d(10));target.addView(r);
                r.setOnClickListener(v->{query=recent;render();});
            }
            header(target,"Explore by Language","",null);target.addView(languageChips());
            header(target,"Popular Right Now","View all",()->go("library"));
            if(tracks.isEmpty())target.addView(empty("Popular on YouTube Music","Loading recommendations…"));
            else rows(target,tracks,8);
            return;
        }
        header(target,"YouTube Music results","",null);
        if(q.length()<2){target.addView(empty("Keep typing","Enter at least 2 characters to search."));return;}
        target.addView(empty("Searching…","Results appear as soon as YouTube Music responds."));
        pendingSearch=()->musicExecutor.execute(()->{
            try{
                List<Track> found=YtMusicCatalog.search(q);
                runOnUiThread(()->{
                    if(generation!=searchGeneration||!screen.equals("search"))return;
                    mergeTracks(found);target.removeAllViews();
                    header(target,"YouTube Music results",found.size()+" songs",null);
                    if(found.isEmpty())target.addView(empty("No matches found","Try another search or spelling."));
                    else rows(target,found,found.size());
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(generation!=searchGeneration||!screen.equals("search"))return;
                    target.removeAllViews();header(target,"YouTube Music results","",null);
                    target.addView(empty("Search unavailable","YouTube Music could not be reached. Tap search to retry."));
                });
            }
        });
        handler.postDelayed(pendingSearch,420);
    }
    View libraryPage(){LinearLayout p=col();p.addView(txt("Your Library",28,true,INK));space(p,8);p.addView(txt("Your saved music and playlists",14,false,MUTED));space(p,14);TextView liked=txt("♥   Liked Songs    "+store.getFavoriteIds().size(),16,true,INK);liked.setPadding(d(15),d(17),d(15),d(17));liked.setBackground(box(SURFACE,16,LINE));p.addView(liked);liked.setOnClickListener(v->{playlist="Liked Songs";screen="detail";render();});header(p,"Your music",tracks.size()+" tracks",null);if(tracks.isEmpty())p.addView(empty("Your library is ready","Your liked songs and playlists will appear here."));else rows(p,tracks,tracks.size());return scroll(p);}
    View playlistsPage(){LinearLayout p=col();LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);h.addView(txt("Playlists",28,true,INK),new LinearLayout.LayoutParams(0,-2,1));TextView create=pill("+ Create");h.addView(create);create.setOnClickListener(v->createPlaylist(null));p.addView(h);space(p,12);TextView liked=txt("♥   Liked Songs\n      "+store.getFavoriteIds().size()+" songs",16,true,INK);liked.setPadding(d(16),d(16),d(16),d(16));liked.setBackground(box(SURFACE,16,LINE));p.addView(liked);liked.setOnClickListener(v->{playlist="Liked Songs";screen="detail";render();});header(p,"Your collections","",null);List<String> names=store.getPlaylists();if(names.isEmpty())p.addView(empty("Make it yours","Create a playlist and add songs from the ⋮ menu."));for(String n:names){TextView r=txt("♫   "+n+"\n      "+store.getPlaylistTrackIds(n).size()+" songs",15,true,INK);r.setPadding(d(14),d(14),d(14),d(14));r.setBackground(box(SURFACE,14,LINE));LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,-2);q.bottomMargin=d(9);p.addView(r,q);r.setOnClickListener(v->{playlist=n;screen="detail";render();});}return scroll(p);}
    View detailPage(){LinearLayout p=col();TextView back=txt("‹  Your playlists",14,true,MUTED);p.addView(back);back.setOnClickListener(v->{screen="playlists";render();});space(p,14);p.addView(txt(playlist,28,true,INK));List<Track> list=tracksFor("Liked Songs".equals(playlist)?store.getFavoriteIds():store.getPlaylistTrackIds(playlist));space(p,8);p.addView(txt(list.size()+" songs",13,false,MUTED));if(list.isEmpty())p.addView(empty("Nothing here yet","Use a track's ⋮ menu to add it to this playlist."));else{TextView play=pill("▶  Play");LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-2,d(42));q.topMargin=d(14);p.addView(play,q);play.setOnClickListener(v->playList(list,0));rows(p,list,list.size());}return scroll(p);}
    View settingsPage(){LinearLayout p=col();p.addView(txt("Settings",28,true,INK));space(p,8);p.addView(txt("Make FluxMusic feel like yours",14,false,MUTED));space(p,16);setting(p,"Music library","Scan this device for audio files","↻",this::scan);setting(p,"Playback","Background playback and lock-screen controls","♫",()->Toast.makeText(this,"Android MediaSession provides background controls.",0).show());setting(p,"Appearance","Warm light theme","☼",()->Toast.makeText(this,"Warm light theme follows your reference.",0).show());setting(p,"Notifications","Manage media notifications","♧",()->{if(Build.VERSION.SDK_INT>=26)startActivity(new android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));});setting(p,"About FluxMusic","Native Android · Java · Media3","ⓘ",()->new AlertDialog.Builder(this).setTitle("FluxMusic").setMessage("A native Java music player for your own music library. Online streaming requires an authorized provider integration.").setPositiveButton("OK",null).show());return scroll(p);}
    View playerPage(){
        LinearLayout p=col();LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);TextView back=txt("⌄",28,false,INK);top.addView(back,new LinearLayout.LayoutParams(d(40),d(42)));back.setOnClickListener(v->{screen=previousTab;render();});LinearLayout source=new LinearLayout(this);source.setOrientation(1);source.setGravity(Gravity.CENTER);source.addView(txt("PLAYING FROM",9,true,MUTED));source.addView(txt(playlist,14,true,INK));top.addView(source,new LinearLayout.LayoutParams(0,-2,1));TextView more=txt("⋮",24,true,INK);top.addView(more);more.setOnClickListener(v->{Track t=active();if(t!=null)trackMenu(more,t);});p.addView(top);
        space(p,18);FrameLayout art=new FrameLayout(this);art.setBackground(gradient(new int[]{0xFFD9A79B,0xFF8C777E,0xFF2D3534},24));TextView glyph=txt("♫",74,true,0xCCFFFFFF);glyph.setGravity(Gravity.CENTER);art.addView(glyph,new FrameLayout.LayoutParams(-1,-1));p.addView(art,new LinearLayout.LayoutParams(-1,d(310)));
        space(p,22);LinearLayout details=new LinearLayout(this);details.setGravity(Gravity.CENTER_VERTICAL);Track t=active();LinearLayout info=new LinearLayout(this);info.setOrientation(1);info.addView(txt(t==null?"Nothing playing":t.title,22,true,INK));info.addView(txt(t==null?"Choose a song from your library":t.artist,15,false,MUTED));details.addView(info,new LinearLayout.LayoutParams(0,-2,1));TextView heart=txt(t!=null&&store.isFavorite(t.id)?"♥":"♡",28,true,INK);details.addView(heart);heart.setOnClickListener(v->{Track a=active();if(a!=null){store.toggleFavorite(a.id);heart.setText(store.isFavorite(a.id)?"♥":"♡");}});p.addView(details);
        space(p,12);seek=new SeekBar(this);seek.setMax(1000);p.addView(seek);seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int n,boolean user){if(user&&elapsed!=null&&player!=null)elapsed.setText(Track.formatDuration(player.getDuration()*n/1000));}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){if(player!=null&&player.getDuration()>0)player.seekTo(player.getDuration()*s.getProgress()/1000);}});
        LinearLayout time=new LinearLayout(this);elapsed=txt("0:00",11,false,MUTED);total=txt(t==null?"0:00":Track.formatDuration(t.duration),11,false,MUTED);time.addView(elapsed,new LinearLayout.LayoutParams(0,-2,1));time.addView(total);p.addView(time);
        space(p,20);LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);TextView shuffle=control("⤨",23),prev=control("|◀",25),next=control("▶|",25),repeat=control("↻",23);playToggle=control(player!=null&&player.isPlaying()?"Ⅱ":"▶",30);playToggle.setTextColor(Color.WHITE);playToggle.setBackground(box(DARK,50,Color.TRANSPARENT));controls.addView(shuffle,new LinearLayout.LayoutParams(0,d(54),1));controls.addView(prev,new LinearLayout.LayoutParams(0,d(54),1));controls.addView(playToggle,new LinearLayout.LayoutParams(d(68),d(68)));controls.addView(next,new LinearLayout.LayoutParams(0,d(54),1));controls.addView(repeat,new LinearLayout.LayoutParams(0,d(54),1));p.addView(controls);
        shuffle.setOnClickListener(v->{if(player!=null){player.setShuffleModeEnabled(!player.getShuffleModeEnabled());shuffle.setTextColor(player.getShuffleModeEnabled()?0xFFB16E4D:INK);}});prev.setOnClickListener(v->{if(player!=null)player.seekToPreviousMediaItem();});playToggle.setOnClickListener(v->toggle());next.setOnClickListener(v->{if(player!=null)player.seekToNextMediaItem();});repeat.setOnClickListener(v->{if(player!=null){int m=player.getRepeatMode()==Player.REPEAT_MODE_ONE?Player.REPEAT_MODE_OFF:Player.REPEAT_MODE_ONE;player.setRepeatMode(m);repeat.setTextColor(m==Player.REPEAT_MODE_ONE?0xFFB16E4D:INK);}});
        space(p,24);LinearLayout footer=new LinearLayout(this);TextView device=pill("▣  This device  ⌄"),queue=pill("☷  Queue ("+(player==null?0:player.getMediaItemCount())+")");footer.addView(device,new LinearLayout.LayoutParams(0,d(48),1));footer.addView(new View(this),new LinearLayout.LayoutParams(d(10),1));footer.addView(queue,new LinearLayout.LayoutParams(0,d(48),1));p.addView(footer);device.setOnClickListener(v->Toast.makeText(this,"Playing music from this device",0).show());queue.setOnClickListener(v->showQueue());handler.removeCallbacks(ticker);handler.post(ticker);return scroll(p);
    }
    void syncProgress(){if(!screen.equals("player")||player==null||seek==null)return;long duration=player.getDuration(),position=player.getCurrentPosition();if(duration>0){seek.setProgress((int)Math.min(1000,position*1000/duration));if(elapsed!=null)elapsed.setText(Track.formatDuration(position));if(total!=null)total.setText(Track.formatDuration(duration));}}
    TextView pill(String s){TextView t=txt(s,13,true,INK);t.setGravity(Gravity.CENTER);t.setPadding(d(12),0,d(12),0);t.setBackground(box(SURFACE,24,LINE));return t;}
    TextView control(String s,int size){TextView t=txt(s,size,true,INK);t.setGravity(Gravity.CENTER);return t;}
    void setting(LinearLayout p,String title,String sub,String glyph,Runnable action){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(d(14),d(14),d(12),d(14));row.setBackground(box(SURFACE,15,LINE));row.addView(txt(glyph,22,true,INK),new LinearLayout.LayoutParams(d(38),-2));LinearLayout m=new LinearLayout(this);m.setOrientation(1);m.addView(txt(title,14,true,INK));m.addView(txt(sub,11,false,MUTED));row.addView(m,new LinearLayout.LayoutParams(0,-2,1));row.addView(txt("›",22,false,MUTED));LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,-2);q.bottomMargin=d(9);p.addView(row,q);row.setOnClickListener(v->action.run());}
    View empty(String title,String message){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(d(17),d(18),d(17),d(18));c.setBackground(box(SURFACE,16,LINE));c.addView(txt(title,16,true,INK));space(c,5);c.addView(txt(message,13,false,MUTED));return c;}
    void horizontalTracks(LinearLayout p,List<Track> list){if(list.isEmpty()){p.addView(txt("Play a song and it will appear here.",13,false,MUTED));return;}HorizontalScrollView h=new HorizontalScrollView(this);h.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(this);for(Track t:list){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.addView(art(t,102));TextView title=txt(t.title,12,true,INK);title.setMaxLines(1);card.addView(title);TextView artist=txt(t.artist,11,false,MUTED);artist.setMaxLines(1);card.addView(artist);LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(d(102),-2);q.rightMargin=d(10);row.addView(card,q);card.setOnClickListener(v->playList(list,list.indexOf(t)));}h.addView(row);p.addView(h);}
    TextView art(Track t,int size){TextView v=txt("♫",26,true,Color.WHITE);v.setGravity(Gravity.CENTER);int[][] colors={{0xFF6F8790,0xFFB9C7C4},{0xFFBA8C75,0xFF443C3B},{0xFF526A66,0xFFB6A28E},{0xFF7B718B,0xFFCEC1B4},{0xFF627B9A,0xFFD4B9A2}};v.setBackground(gradient(colors[Math.abs(t.id.hashCode())%colors.length],13));v.setLayoutParams(new LinearLayout.LayoutParams(d(size),d(size)));return v;}
    void rows(LinearLayout p,List<Track> list,int limit){for(int i=0;i<Math.min(limit,list.size());i++){Track t=list.get(i);LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,d(6),0,d(6));r.addView(art(t,48));LinearLayout m=new LinearLayout(this);m.setOrientation(1);m.setPadding(d(11),0,d(8),0);TextView name=txt(t.title,14,true,INK);name.setMaxLines(1);TextView artist=txt(t.artist,12,false,MUTED);artist.setMaxLines(1);m.addView(name);m.addView(artist);r.addView(m,new LinearLayout.LayoutParams(0,-2,1));r.addView(txt(Track.formatDuration(t.duration),11,false,MUTED));TextView more=txt("⋮",21,true,INK);more.setPadding(d(12),d(5),0,d(5));r.addView(more);more.setOnClickListener(v->trackMenu(more,t));r.setOnClickListener(v->playList(list,list.indexOf(t)));p.addView(r);View line=new View(this);line.setBackgroundColor(LINE);p.addView(line,new LinearLayout.LayoutParams(-1,d(1)));}}
    List<Track> recent(){List<Track> r=tracksFor(store.getRecentIds());return r.isEmpty()?new ArrayList<>(tracks.subList(0,Math.min(8,tracks.size()))):r;}
    List<Track> tracksFor(List<String> ids){List<Track> r=new ArrayList<>();for(String id:ids)for(Track t:tracks)if(t.id.equals(id)){r.add(t);break;}return r;}
    void searchFor(String s){query=s;go("search");}
    void playList(List<Track> list,int index){
        if(list.isEmpty())return;
        if(player==null){Toast.makeText(this,"Connecting to playback…",0).show();return;}
        int start=Math.max(0,Math.min(index,list.size()-1));
        Track selected=list.get(start);
        Toast.makeText(this,"Preparing "+selected.title+"…",Toast.LENGTH_SHORT).show();
        playbackExecutor.execute(()->{
            try{
                String stream=YtMusicCatalog.resolveAudioUrl(selected.id);
                MediaItem first=selected.toMediaItem(Uri.parse(stream));
                runOnUiThread(()->{
                    if(player==null)return;
                    player.setMediaItem(first);player.prepare();player.play();
                    active=selected;store.addRecent(selected.id);previousTab=tab;screen="player";render();
                });
                for(int offset=1;offset<list.size();offset++){
                    Track next=list.get((start+offset)%list.size());
                    try{
                        String nextUrl=YtMusicCatalog.resolveAudioUrl(next.id);
                        MediaItem item=next.toMediaItem(Uri.parse(nextUrl));
                        runOnUiThread(()->{if(player!=null)player.addMediaItem(item);});
                    }catch(Exception ignored){}
                }
            }catch(Exception e){
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Could not start playback")
                    .setMessage(e.getMessage()==null?"The provider did not return a playable audio stream.":e.getMessage())
                    .setPositiveButton("Retry",(d,w)->playList(list,start)).setNegativeButton("Close",null).show());
            }
        });
    }
    void addResolvedToQueue(Track track,boolean playNext){
        if(player==null){Toast.makeText(this,"Open a song first to create a queue.",0).show();return;}
        playbackExecutor.execute(()->{
            try{
                String url=YtMusicCatalog.resolveAudioUrl(track.id);
                MediaItem item=track.toMediaItem(Uri.parse(url));
                runOnUiThread(()->{
                    if(player!=null){
                        if(playNext)player.addMediaItem(Math.min(player.getCurrentMediaItemIndex()+1,player.getMediaItemCount()),item);
                        else player.addMediaItem(item);
                        Toast.makeText(this,"Added to queue",Toast.LENGTH_SHORT).show();
                    }
                });
            }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Could not prepare this track for the queue.",Toast.LENGTH_LONG).show());}
        });
    }
    void toggle(){if(player==null)return;if(player.isPlaying())player.pause();else player.play();syncMini();}
    void syncMini(){if(mini==null)return;Track t=active();if(t==null){mini.setVisibility(View.GONE);return;}mini.setVisibility(screen.equals("player")?View.GONE:View.VISIBLE);miniName.setText(t.title);miniArtist.setText(t.artist);miniToggle.setText(player!=null&&player.isPlaying()?"Ⅱ":"▶");if(playToggle!=null)playToggle.setText(player!=null&&player.isPlaying()?"Ⅱ":"▶");}
    void trackMenu(View anchor,Track t){PopupMenu m=new PopupMenu(this,anchor);m.getMenu().add("Play next");m.getMenu().add("Add to queue");m.getMenu().add("Add to playlist");m.getMenu().add(store.isFavorite(t.id)?"Remove from Liked Songs":"Add to Liked Songs");m.setOnMenuItemClickListener(item->{String s=item.getTitle().toString();if(s.equals("Play next")){addResolvedToQueue(t,true);}else if(s.equals("Add to queue")){addResolvedToQueue(t,false);}else if(s.equals("Add to playlist"))choosePlaylist(t);else{store.toggleFavorite(t.id);Toast.makeText(this,store.isFavorite(t.id)?"Added to Liked Songs":"Removed from Liked Songs",0).show();}return true;});m.show();}
    void choosePlaylist(Track t){List<String> names=store.getPlaylists();if(names.isEmpty()){new AlertDialog.Builder(this).setMessage("Create a playlist first?").setNegativeButton("Cancel",null).setPositiveButton("Create",(a,b)->createPlaylist(t)).show();return;}names.add(0,"Create new playlist…");String[] a=names.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("Add to playlist").setItems(a,(d,w)->{if(w==0)createPlaylist(t);else store.addToPlaylist(a[w],t.id);}).show();}
    void createPlaylist(Track add){EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Playlist name");new AlertDialog.Builder(this).setTitle("New playlist").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create",(d,w)->{String n=input.getText().toString().trim();if(n.isEmpty())return;store.createPlaylist(n);if(add!=null)store.addToPlaylist(n,add.id);playlist=n;tab="playlists";screen="detail";render();}).show();}
    void showQueue(){if(player==null||player.getMediaItemCount()==0){Toast.makeText(this,"The queue is empty.",0).show();return;}String[] titles=new String[player.getMediaItemCount()];for(int i=0;i<titles.length;i++){MediaItem m=player.getMediaItemAt(i);titles[i]=(i==player.getCurrentMediaItemIndex()?"▶  ":"")+(m.mediaMetadata.title==null?"Unknown track":m.mediaMetadata.title.toString());}new AlertDialog.Builder(this).setTitle("Queue").setItems(titles,(d,w)->player.seekTo(w,0)).setPositiveButton("Done",null).show();}
    @Override public void onMediaItemTransition(MediaItem item,int reason){if(item!=null)for(Track t:tracks)if(t.id.equals(item.mediaId)){active=t;store.addRecent(t.id);break;}syncMini();if(screen.equals("player"))render();}
    @Override public void onIsPlayingChanged(boolean playing){syncMini();}
    @Override public void onPlaybackStateChanged(int state){syncMini();}
    @Override protected void onResume(){super.onResume();if(tracks.isEmpty()&&!discoveryLoading)loadDiscovery();}
    @Override protected void onDestroy(){handler.removeCallbacks(ticker);if(controllerFuture!=null)MediaController.releaseFuture(controllerFuture);musicExecutor.shutdownNow();playbackExecutor.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(screen.equals("player"))screen=previousTab;else if(screen.equals("detail"))screen="playlists";else if(!screen.equals("home"))screen="home";else{super.onBackPressed();return;}tab=screen.equals("detail")?"playlists":screen;render();}
}
