package ru.iptv.app;

import android.app.Activity;
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
import org.videolan.libvlc.util.VLCVideoLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int BG=0xff090f19, PANEL=0xff111c2b, BLUE=0xff187ce5, WHITE=Color.WHITE, SUB=0xffa2b3c8;
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
        TextView title=text("MTS_IPTV",27,true);title.setPadding(dp(18),dp(22),dp(12),dp(8));
        page.addView(title);
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
    private void startPlayer(){
        if(!viewing || surface==null || urls.isEmpty())return;
        releasePlayer();acquireWifi();
        try{
            vlc=new LibVLC(this,new ArrayList<String>());
            player=new MediaPlayer(vlc);
            MediaPlayer p=player;
            player.attachViews(surface,null,false,false);
            player.setEventListener(event->{
                if(p!=player)return;
                if(event.type==MediaPlayer.Event.Playing)
                    runOnUiThread(()->{if(p==player)showStatus(channelLabel(current));});
                if(event.type==MediaPlayer.Event.EncounteredError)
                    runOnUiThread(()->{if(p==player)showStatus("Нет сигнала: "+channelLabel(current));});
            });
            Media m=new Media(vlc,Uri.parse(urls.get(current)));
            m.addOption(":network-caching=1000");
            m.addOption(":live-caching=1000");
            m.addOption(":no-video-title-show");
            player.setMedia(m);m.release();
            showStatus("Подключение: "+channelLabel(current));
            player.play();
        }catch(Exception e){showStatus("Ошибка воспроизведения: "+e.getMessage());}
    }
    private void releasePlayer(){
        if(player!=null){
            try{player.stop();player.detachViews();player.release();}catch(Exception ignored){}
            player=null;
        }
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
        startPlayer();
    }
    private void showMenu(){
        if(menu!=null&&menu.isShowing())return;
        menu=new Dialog(this);menu.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(BG);
        box.addView(text("MTS_IPTV  ·  22 канала",22,true));
        ListView list=new ListView(this);list.setDividerHeight(dp(4));list.setPadding(dp(8),0,dp(8),dp(8));
        list.setAdapter(adapter());
        list.setOnItemClickListener((parent,v,position,id)->{menu.dismiss();switchTo(position);});
        box.addView(list,new LinearLayout.LayoutParams(-1,-1));
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
