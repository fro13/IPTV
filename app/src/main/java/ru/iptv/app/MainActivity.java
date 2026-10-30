package ru.iptv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int BG=0xff090f19, PANEL=0xff111c2b, BLUE=0xff187ce5, WHITE=Color.WHITE, SUB=0xffa2b3c8;
    private static final int[] CACHE_OPTIONS={500,2000,4000};
    private static final String[] CACHE_LABELS={"Быстрый — 0,5 с","Сбалансированный — 2 с","Устойчивый — 4 с"};
    private static final int MAX_RECOVERIES=3;
    private static final long STARTUP_TIMEOUT_MS=25000, STALL_TIMEOUT_MS=12000, CHECK_INTERVAL_MS=2000;
    private final ArrayList<String> names=new ArrayList<>();
    private final ArrayList<String> urls=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int current=0;
    private boolean tv=false, viewing=false, visible=true;
    private FrameLayout frame;
    private VLCVideoLayout surface;
    private TextView status;
    private LinearLayout controls;
    private LibVLC vlc;
    private MediaPlayer player;
    private Media activeMedia;
    private long playStartedAt, lastFrameAt, lastRecoveryAt, lastTimeValue=-1;
    private long lastDisplayedPictures=-1;
    private boolean videoStarted=false, statsAvailable=false, playingEventSeen=false, playbackError=false;
    private int recoveryAttempts=0;
    private final Runnable watchdog=new Runnable(){
        @Override public void run(){
            if(!viewing || player==null || activeMedia==null)return;
            long now=SystemClock.elapsedRealtime();
            try{
                IMedia.Stats stats=activeMedia.getStats();
                if(stats!=null){
                    statsAvailable=true;
                    long pictures=stats.displayedPictures;
                    if(pictures>0 && pictures>lastDisplayedPictures){
                        lastDisplayedPictures=pictures;
                        videoStarted=true;
                        playbackError=false;
                        lastFrameAt=now;
                    }
                }
            }catch(Exception ignored){}
            if(videoStarted && recoveryAttempts>0 && now-lastRecoveryAt>60000 && now-lastFrameAt<CHECK_INTERVAL_MS*2)
                recoveryAttempts=0;
            // Some devices do not expose video frame stats for UDP. Playing alone is
            // insufficient evidence of a freeze; avoid needless restart loops.
            if(!statsAvailable && playingEventSeen && lastTimeValue<0 && !playbackError){
                handler.postDelayed(this,CHECK_INTERVAL_MS);
                return;
            }
            long timeout=videoStarted?STALL_TIMEOUT_MS:STARTUP_TIMEOUT_MS;
            long elapsed=now-(videoStarted?lastFrameAt:playStartedAt);
            if((playbackError || elapsed>=timeout) && recoveryAttempts<MAX_RECOVERIES){
                recoveryAttempts++;
                lastRecoveryAt=now;
                showStatus("Восстановление потока ("+recoveryAttempts+"/"+MAX_RECOVERIES+")");
                startPlayer();
                return;
            }
            if((playbackError || elapsed>=timeout) && recoveryAttempts==MAX_RECOVERIES){
                showStatus("Нет видеосигнала. Выберите канал ещё раз.");
                recoveryAttempts++;
            }
            handler.postDelayed(this,CHECK_INTERVAL_MS);
        }
    };
    private WifiManager.MulticastLock lock;
    private Dialog menu;
    private final Runnable hideStatus=()->{if(status!=null && tv)status.setVisibility(View.GONE);};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        UiModeManager ui=(UiModeManager)getSystemService(UI_MODE_SERVICE);
        tv=(ui!=null && ui.getCurrentModeType()==Configuration.UI_MODE_TYPE_TELEVISION)
            || getPackageManager().hasSystemFeature("android.software.leanback");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            InputStream in=getAssets().open("channels.json");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            in.close();
            JSONArray arr=new JSONArray(new String(out.toByteArray(),StandardCharsets.UTF_8));
            for(int i=0;i<arr.length();i++){
                JSONObject item=arr.getJSONObject(i);
                names.add(item.getString("name"));urls.add(item.getString("url"));
            }
        } catch(Exception e){TextView error=new TextView(this);error.setText("Ошибка каналов: "+e);setContentView(error);return;}
        current=Math.max(0,Math.min(names.size()-1,getPreferences(0).getInt("last",0)));
        if(tv)showPlayer();else showBrowser();
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;
    }
    private TextView text(String s,int size,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(WHITE);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(16),dp(11),dp(16),dp(11));return t;
    }
    private Button button(String s){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);
        b.setTextColor(WHITE);b.setBackground(bg(PANEL,12));return b;
    }
    private String channelLabel(int i){return (i+1)+".  "+names.get(i);}
    private ArrayAdapter<String> adapter(){
        ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<names.size();i++)labels.add(channelLabel(i));
        return new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int pos,View convert,ViewGroup parent){
                TextView t=text(getItem(pos),tv?19:17,pos==current);
                t.setSingleLine(false);
                t.setMinHeight(dp(tv?54:58));
                t.setBackground(bg(pos==current?BLUE:PANEL,10));
                return t;
            }
        };
    }
    private void showBrowser(){
        viewing=false;releasePlayer();
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(BG);
        TextView title=text("МТС IPTV",27,true);title.setPadding(dp(18),dp(22),dp(12),dp(8));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=button("⚙");settings.setTextSize(23);
        settings.setContentDescription("Настройки буферизации");
        settings.setOnClickListener(v->showBufferSettings());
        LinearLayout.LayoutParams gear=new LinearLayout.LayoutParams(dp(55),dp(52));
        gear.setMargins(0,dp(12),dp(14),dp(4));header.addView(settings,gear);
        page.addView(header);
        ListView list=new ListView(this);list.setDividerHeight(dp(4));list.setBackgroundColor(BG);
        list.setPadding(dp(8),dp(4),dp(8),dp(8));list.setClipToPadding(false);
        list.setAdapter(adapter());
        list.setOnItemClickListener((parent,view,pos,id)->{current=pos;showPlayer();});
        page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(page);
    }
    private void showPlayer(){
        viewing=true;visible=true;
        if(tv)getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
        surface=new VLCVideoLayout(this);
        frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        status=text("",tv?20:18,true);
        status.setBackground(bg(0xe8111c2b,12));
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(tv?dp(540):-1,-2,Gravity.BOTTOM);
        sp.setMargins(dp(16),dp(16),dp(16),tv?dp(22):dp(90));
        frame.addView(status,sp);
        if(!tv){
            controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setPadding(dp(8),dp(8),dp(8),dp(8));controls.setBackgroundColor(0xe8111c2b);
            Button back=button("Список");Button prev=button("◀");Button next=button("▶");
            back.setOnClickListener(v->showBrowser());prev.setOnClickListener(v->switchTo(current-1));
            next.setOnClickListener(v->switchTo(current+1));
            controls.addView(back,new LinearLayout.LayoutParams(0,dp(60),2));
            controls.addView(prev,new LinearLayout.LayoutParams(0,dp(60),1));
            controls.addView(next,new LinearLayout.LayoutParams(0,dp(60),1));
            frame.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            frame.setOnClickListener(v->{visible=!visible;controls.setVisibility(visible?View.VISIBLE:View.GONE);status.setVisibility(visible?View.VISIBLE:View.GONE);});
        }
        setContentView(frame);
        applyPhoneOrientation(getResources().getConfiguration());
        getPreferences(0).edit().putInt("last",current).apply();
        startPlayer();
    }
    // Keep the current LibVLC session and SurfaceView attached when the phone rotates.
    // The OS delivers orientation and screen-size changes here instead of recreating the Activity.
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        applyPhoneOrientation(config);
    }

    private void applyPhoneOrientation(Configuration config) {
        if (tv || !viewing) return;
        boolean landscape=config.orientation==Configuration.ORIENTATION_LANDSCAPE;
        if (landscape) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            visible=false;
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            visible=true;
        }
        if (controls!=null) controls.setVisibility(visible?View.VISIBLE:View.GONE);
        if (status!=null) status.setVisibility(visible?View.VISIBLE:View.GONE);
    }

    private void acquireWifi(){
        try{
            WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wifi!=null&&lock==null){
                lock=wifi.createMulticastLock("MTS_IPTV_UDP");lock.setReferenceCounted(false);lock.acquire();
            }
        }catch(Exception ignored){}
    }
    private int cacheMs(){
        int saved=getPreferences(0).getInt("buffer_ms",2000);
        for(int value:CACHE_OPTIONS)if(value==saved)return saved;
        return 2000;
    }
    private String cacheName(){
        for(int i=0;i<CACHE_OPTIONS.length;i++)if(CACHE_OPTIONS[i]==cacheMs())return CACHE_LABELS[i];
        return CACHE_LABELS[1];
    }
    private void showBufferSettings(){
        int checked=1;
        for(int i=0;i<CACHE_OPTIONS.length;i++)if(cacheMs()==CACHE_OPTIONS[i])checked=i;
        new AlertDialog.Builder(this)
            .setTitle("Буферизация видео")
            .setSingleChoiceItems(CACHE_LABELS,checked,(dialog,which)->{
                getPreferences(0).edit().putInt("buffer_ms",CACHE_OPTIONS[which]).apply();
                dialog.dismiss();
                if(viewing)showStatus("Буфер: "+CACHE_LABELS[which]+" (со следующего канала)");
            })
            .setNegativeButton("Отмена",null)
            .show();
    }
    private void onPlayerEvent(MediaPlayer p,MediaPlayer.Event event){
        if(p!=player || !viewing)return;
        if(event.type==MediaPlayer.Event.Playing){
            playingEventSeen=true;
            playbackError=false;
            showStatus(channelLabel(current));
        }else if(event.type==MediaPlayer.Event.EncounteredError){
            playbackError=true;
            showStatus("Нет сигнала: "+channelLabel(current));
        }
        else if(event.type==MediaPlayer.Event.TimeChanged && !statsAvailable){
            long timestamp=event.getTimeChanged();
            if(timestamp>0 && timestamp!=lastTimeValue){
                lastTimeValue=timestamp;
                videoStarted=true;
                lastFrameAt=SystemClock.elapsedRealtime();
            }
        }
    }
    private void startPlayer(){
        if(!viewing || surface==null || urls.isEmpty())return;
        handler.removeCallbacks(watchdog);
        acquireWifi();
        try{
            if(vlc==null)vlc=new LibVLC(this,new ArrayList<String>());
            if(player==null){
                player=new MediaPlayer(vlc);
                MediaPlayer p=player;
                player.attachViews(surface,null,false,false);
                player.setEventListener(event->runOnUiThread(()->onPlayerEvent(p,event)));
            }else{
                // Keep LibVLC engine, MediaPlayer and attached video surface.
                player.stop();
            }
            if(activeMedia!=null){activeMedia.release();activeMedia=null;}
            activeMedia=new Media(vlc,Uri.parse(urls.get(current)));
            int cache=cacheMs();
            activeMedia.addOption(":network-caching="+cache);
            activeMedia.addOption(":live-caching="+cache);
            activeMedia.addOption(":no-video-title-show");
            player.setMedia(activeMedia);
            playStartedAt=SystemClock.elapsedRealtime();
            lastFrameAt=playStartedAt;
            lastDisplayedPictures=-1;
            lastTimeValue=-1;
            videoStarted=false;
            statsAvailable=false;
            playingEventSeen=false;
            playbackError=false;
            showStatus("Подключение: "+channelLabel(current));
            player.play();
            handler.postDelayed(watchdog,CHECK_INTERVAL_MS);
        }catch(Exception e){
            showStatus("Ошибка воспроизведения: "+e.getMessage());
            handler.postDelayed(watchdog,CHECK_INTERVAL_MS);
        }
    }
    private void releasePlayer(){
        handler.removeCallbacks(watchdog);
        if(player!=null){
            try{player.setEventListener(null);player.stop();player.detachViews();player.release();}catch(Exception ignored){}
            player=null;
        }
        if(activeMedia!=null){try{activeMedia.release();}catch(Exception ignored){}activeMedia=null;}
        if(vlc!=null){try{vlc.release();}catch(Exception ignored){} vlc=null;}
    }
    private void showStatus(String s){
        if(status==null)return;
        status.setVisibility(View.VISIBLE);
        status.setText(s);
        handler.removeCallbacks(hideStatus);
        if(tv)handler.postDelayed(hideStatus,3800);
        else if(viewing && getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE)handler.postDelayed(()->{if(status!=null && !visible)status.setVisibility(View.GONE);},2600);
    }
    private void switchTo(int i){
        if(names.isEmpty())return;
        current=(i+names.size())%names.size();
        getPreferences(0).edit().putInt("last",current).apply();
        recoveryAttempts=0;
        startPlayer();
    }
    private void showMenu(){
        if(menu!=null&&menu.isShowing())return;
        menu=new Dialog(this);menu.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(BG);
        box.addView(text("МТС IPTV  ·  22 канала",22,true));
        Button settings=button("⚙ Буферизация: "+cacheName());
        settings.setOnClickListener(v->{menu.dismiss();showBufferSettings();});
        box.addView(settings,new LinearLayout.LayoutParams(-1,dp(58)));
        ListView list=new ListView(this);list.setDividerHeight(dp(4));list.setPadding(dp(8),0,dp(8),dp(8));
        list.setAdapter(adapter());
        list.setOnItemClickListener((parent,v,position,id)->{menu.dismiss();switchTo(position);});
        box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        menu.setContentView(box);
        Window w=menu.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(tv?dp(570):-1,(int)(getResources().getDisplayMetrics().heightPixels*0.85));w.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);}
        menu.show();
        if(menu.getWindow()!=null){menu.getWindow().setLayout(tv?dp(570):(int)(getResources().getDisplayMetrics().widthPixels*0.96),(int)(getResources().getDisplayMetrics().heightPixels*0.88));}
        list.setSelection(current);list.requestFocus();
    }
    @Override public boolean onKeyDown(int keyCode,KeyEvent e){
        if(tv&&viewing&&(menu==null||!menu.isShowing())){
            if(keyCode==KeyEvent.KEYCODE_DPAD_UP||keyCode==KeyEvent.KEYCODE_CHANNEL_UP){switchTo(current+1);return true;}
            if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN||keyCode==KeyEvent.KEYCODE_CHANNEL_DOWN){switchTo(current-1);return true;}
            if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){showMenu();return true;}
        }
        return super.onKeyDown(keyCode,e);
    }
    @Override public void onBackPressed(){
        if(menu!=null&&menu.isShowing()){menu.dismiss();return;}
        if(!tv&&viewing){showBrowser();return;}
        if(tv&&viewing){showMenu();return;}
        super.onBackPressed();
    }
    @Override protected void onStop(){
        releasePlayer();
        if(lock!=null){try{if(lock.isHeld())lock.release();}catch(Exception ignored){}lock=null;}
        super.onStop();
    }
    @Override protected void onStart(){super.onStart();if(viewing&&player==null&&surface!=null)startPlayer();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);releasePlayer();super.onDestroy();}
}
