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
    private static final int BG=0xff070f1b, PANEL=0xff0e1d2e, BLUE=0xff168cff, WHITE=Color.WHITE, SUB=0xff9eb4d1;
    private static final int BORDER=0xff243b53, SELECTED=0xff102f50, DIALOG_BG=0xff0a192a;
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
    private FrameLayout phoneRoot,videoHost,phoneControlsHost;
    private LinearLayout phonePage;
    private TextView phoneChannelTitle;
    private ListView phoneList;
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
    private GradientDrawable outlined(int color,int radius,int stroke){
        GradientDrawable g=bg(color,radius);g.setStroke(dp(1),stroke);return g;
    }
    private GradientDrawable blueGradient(int radius){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff279cff,0xff075dd2});
        g.setCornerRadius(dp(radius));return g;
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);return t;
    }
    private TextView text(String s,int size,boolean bold){
        TextView t=label(s,size,WHITE,bold);
        t.setPadding(dp(16),dp(11),dp(16),dp(11));return t;
    }
    private Button button(String s){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);
        b.setTextColor(WHITE);b.setTextSize(13);
        b.setPadding(dp(4),0,dp(4),0);
        b.setMinHeight(0);b.setMinimumHeight(0);
        b.setBackground(outlined(PANEL,12,BORDER));return b;
    }
    private String channelLabel(int i){return (i+1)+".  "+names.get(i);}
    private String monogram(String name){
        if(name.contains("Первый"))return "1";
        if(name.contains("Россия 1"))return "Р1";
        if(name.contains("Матч"))return "М";
        if(name.contains("НТВ"))return "НТВ";
        if(name.contains("СТС"))return "СТС";
        String clean=name.trim();
        return clean.substring(0,Math.min(clean.length(),2)).toUpperCase();
    }
    private ArrayAdapter<String> adapter(){
        ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<names.size();i++)labels.add(names.get(i));
        return new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int pos,View convert,ViewGroup parent){
                LinearLayout row=new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10),dp(3),dp(6),dp(3));
                boolean selected=pos==current;
                row.setBackground(outlined(selected?SELECTED:PANEL,12,selected?BLUE:BORDER));
                row.setMinimumHeight(dp(tv?54:58));
                TextView number=label((pos+1)+".",tv?17:14,SUB,false);
                number.setGravity(Gravity.CENTER);
                row.addView(number,new LinearLayout.LayoutParams(dp(30),dp(46)));
                if(!tv){
                    TextView logo=label(monogram(names.get(pos)),12,WHITE,true);
                    logo.setGravity(Gravity.CENTER);
                    logo.setBackground(bg(pos%3==0?0xff124f9e:(pos%3==1?0xff18426c:0xff215072),9));
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(43),dp(38));
                    lp.setMargins(dp(3),0,dp(11),0);row.addView(logo,lp);
                }
                TextView name=label(names.get(pos),tv?19:16,selected?WHITE:0xffd4e0ed,selected);
                name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                if(!tv){
                    boolean favorite=getPreferences(0).getBoolean("favorite_"+pos,false);
                    TextView star=label(favorite?"★":"☆",27,favorite?BLUE:0xff718eae,false);
                    star.setGravity(Gravity.CENTER);
                    star.setContentDescription(favorite?"Убрать из избранного":"Добавить в избранное");
                    star.setOnClickListener(v->{
                        getPreferences(0).edit().putBoolean("favorite_"+pos,!favorite).apply();
                        if(parent instanceof ListView)((ListView)parent).invalidateViews();
                    });
                    star.setFocusable(false);
                    row.addView(star,new LinearLayout.LayoutParams(dp(44),dp(46)));
                }
                return row;
            }
        };
    }
    private LinearLayout navigationBar(boolean browser,ListView browserList){
        LinearLayout bar=new LinearLayout(this);
        bar.setPadding(dp(7),dp(8),dp(7),dp(8));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(bg(0xff091726,14));
        Button prev=button("◀  Предыдущий");
        Button list=button("☷  Список");
        Button next=button("Следующий  ▶");
        list.setBackground(outlined(0xff103e70,12,BLUE));
        list.setTextColor(WHITE);
        prev.setOnClickListener(v->{if(browser){current=(current+names.size()-1)%names.size();showPlayer();}else switchTo(current-1);});
        next.setOnClickListener(v->{if(browser){current=(current+1)%names.size();showPlayer();}else switchTo(current+1);});
        list.setOnClickListener(v->{
            if(browser){if(browserList!=null)browserList.smoothScrollToPosition(current);}
            else showBrowser();
        });
        Button[] items={prev,list,next};
        float[] weights={1.15f,1.12f,1.15f};
        for(int i=0;i<items.length;i++){
            items[i].setTextSize(11);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(54),weights[i]);
            lp.setMargins(dp(3),0,dp(3),0);
            bar.addView(items[i],lp);
        }
        return bar;
    }

    private void showBrowser(){
        viewing=false;releasePlayer();
        status=null;controls=null;phoneRoot=null;phoneList=null;phoneChannelTitle=null;
        if(!tv){
            getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(BG);
        TextView title=text("МТС IPTV",27,true);title.setPadding(dp(18),dp(17),dp(12),dp(13));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=button("⚙");settings.setTextSize(23);
        settings.setContentDescription("Настройки буферизации");
        settings.setOnClickListener(v->showBufferSettings());
        LinearLayout.LayoutParams gear=new LinearLayout.LayoutParams(dp(52),dp(50));
        gear.setMargins(0,dp(10),dp(13),dp(8));header.addView(settings,gear);
        page.addView(header);
        ListView list=new ListView(this);list.setDividerHeight(dp(4));list.setDivider(null);
        list.setBackgroundColor(BG);list.setVerticalScrollBarEnabled(false);
        list.setPadding(dp(12),dp(3),dp(12),dp(6));list.setClipToPadding(false);
        list.setAdapter(adapter());
        list.setOnItemClickListener((parent,view,pos,id)->{current=pos;recoveryAttempts=0;showPlayer();});
        page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        page.addView(navigationBar(true,list),new LinearLayout.LayoutParams(-1,-2));
        setContentView(page);
        list.setSelection(current);
    }
    private void showPlayer(){
        viewing=true;visible=true;
        if(tv)getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
        surface=new VLCVideoLayout(this);
        frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        status=text("",tv?20:14,true);
        status.setBackground(bg(0xe8111c2b,12));
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(tv?dp(540):-1,-2,Gravity.BOTTOM);
        sp.setMargins(dp(12),dp(12),dp(12),dp(9));
        frame.addView(status,sp);
        if(!tv){
            getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
            phoneRoot=new FrameLayout(this);phoneRoot.setBackgroundColor(BG);
            phonePage=new LinearLayout(this);
            phonePage.setOrientation(LinearLayout.VERTICAL);phonePage.setBackgroundColor(BG);
            LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
            Button back=button("‹");back.setTextSize(31);
            back.setOnClickListener(v->showBrowser());
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(46),dp(48));
            bp.setMargins(dp(10),dp(6),dp(6),dp(6));header.addView(back,bp);
            TextView title=label("МТС IPTV",23,WHITE,true);
            header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
            Button settings=button("⚙");settings.setTextSize(23);
            settings.setOnClickListener(v->showBufferSettings());
            LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(47),dp(46));
            gp.setMargins(dp(4),dp(6),dp(12),dp(6));header.addView(settings,gp);
            phonePage.addView(header);
            videoHost=new FrameLayout(this);videoHost.setBackgroundColor(Color.BLACK);
            int videoHeight=(int)(getResources().getDisplayMetrics().widthPixels*9f/16f);
            LinearLayout.LayoutParams vlp=new LinearLayout.LayoutParams(-1,videoHeight);
            vlp.setMargins(dp(10),dp(4),dp(10),0);phonePage.addView(videoHost,vlp);
            phoneChannelTitle=label(names.get(current),19,WHITE,true);
            phoneChannelTitle.setPadding(dp(16),dp(10),dp(16),dp(7));
            phonePage.addView(phoneChannelTitle,new LinearLayout.LayoutParams(-1,dp(51)));
            LinearLayout listHeading=new LinearLayout(this);
            listHeading.setGravity(Gravity.CENTER_VERTICAL);
            listHeading.setPadding(dp(17),dp(4),dp(12),dp(2));
            listHeading.addView(label("Каналы",20,WHITE,true));
            phonePage.addView(listHeading,new LinearLayout.LayoutParams(-1,dp(39)));
            phoneList=new ListView(this);
            phoneList.setDivider(null);phoneList.setDividerHeight(dp(4));
            phoneList.setVerticalScrollBarEnabled(false);
            phoneList.setPadding(dp(10),dp(2),dp(10),dp(5));phoneList.setClipToPadding(false);
            phoneList.setBackgroundColor(BG);
            phoneList.setAdapter(adapter());
            phoneList.setOnItemClickListener((p,v,pos,id)->switchTo(pos));
            phonePage.addView(phoneList,new LinearLayout.LayoutParams(-1,0,1));
            phoneControlsHost=new FrameLayout(this);
            controls=navigationBar(false,null);
            phoneControlsHost.addView(controls,new FrameLayout.LayoutParams(-1,-2));
            phonePage.addView(phoneControlsHost,new LinearLayout.LayoutParams(-1,-2));
            setContentView(phoneRoot);
            frame.setOnClickListener(v->{
                if(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE){
                    visible=!visible;
                    controls.setVisibility(visible?View.VISIBLE:View.GONE);
                    status.setVisibility(visible?View.VISIBLE:View.GONE);
                }
            });
        }else setContentView(frame);
        applyPhoneOrientation(getResources().getConfiguration());
        getPreferences(0).edit().putInt("last",current).apply();
        if(phoneList!=null)phoneList.setSelection(current);
        startPlayer();
    }
    // Keep the current LibVLC session and SurfaceView attached when the phone rotates.
    // The OS delivers orientation and screen-size changes here instead of recreating the Activity.
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        applyPhoneOrientation(config);
    }


    private void removeParent(View view){
        if(view!=null && view.getParent() instanceof ViewGroup)
            ((ViewGroup)view.getParent()).removeView(view);
    }
    private void applyPhoneOrientation(Configuration config) {
        if (tv || !viewing || phoneRoot==null || frame==null) return;
        boolean landscape=config.orientation==Configuration.ORIENTATION_LANDSCAPE;
        removeParent(frame);
        removeParent(controls);
        phoneRoot.removeAllViews();
        if (landscape) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            phoneRoot.addView(frame,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout.LayoutParams clp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
            frame.addView(controls,clp);
            FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)status.getLayoutParams();
            sp.setMargins(dp(12),dp(12),dp(12),dp(82));status.setLayoutParams(sp);
            visible=false;
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            videoHost.addView(frame,new FrameLayout.LayoutParams(-1,-1));
            phoneControlsHost.addView(controls,new FrameLayout.LayoutParams(-1,-2));
            phoneRoot.addView(phonePage,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)status.getLayoutParams();
            sp.setMargins(dp(9),dp(9),dp(9),dp(8));status.setLayoutParams(sp);
            visible=true;
        }
        controls.setVisibility(visible?View.VISIBLE:View.GONE);
        status.setVisibility(visible?View.VISIBLE:View.GONE);
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

    private LinearLayout bufferChoice(String symbol,String name,String description,String time,
                                     boolean selected,Dialog dialog,int option,int[] pending,
                                     ArrayList<LinearLayout> choices){
        LinearLayout card=new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(11),dp(10),dp(10),dp(10));
        card.setBackground(outlined(selected?SELECTED:PANEL,12,selected?BLUE:BORDER));
        TextView icon=label(symbol,23,BLUE,true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon,new LinearLayout.LayoutParams(dp(41),dp(50)));
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label(name,15,WHITE,true);
        TextView sub=label(description,12,SUB,false);
        words.addView(heading);words.addView(sub);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);
        wp.setMargins(dp(3),0,dp(5),0);card.addView(words,wp);
        TextView amount=label(time,14,selected?BLUE:WHITE,false);
        amount.setGravity(Gravity.CENTER);card.addView(amount,new LinearLayout.LayoutParams(dp(42),dp(50)));
        TextView radio=label(selected?"◉":"○",27,selected?BLUE:0xff557496,false);
        radio.setGravity(Gravity.CENTER);card.addView(radio,new LinearLayout.LayoutParams(dp(32),dp(50)));
        card.setOnClickListener(v->{
            pending[0]=option;
            for(int i=0;i<choices.size();i++){
                boolean isSelected=i==option;
                LinearLayout c=choices.get(i);
                c.setBackground(outlined(isSelected?SELECTED:PANEL,12,isSelected?BLUE:BORDER));
                TextView val=(TextView)c.getChildAt(2);
                TextView mark=(TextView)c.getChildAt(3);
                val.setTextColor(isSelected?BLUE:WHITE);
                mark.setText(isSelected?"◉":"○");
                mark.setTextColor(isSelected?BLUE:0xff557496);
            }
        });
        return card;
    }
    private void showBufferSettings(){
        int chosen=1;
        for(int i=0;i<CACHE_OPTIONS.length;i++)if(cacheMs()==CACHE_OPTIONS[i])chosen=i;
        final int[] pending={chosen};
        Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(15),dp(17),dp(15),dp(15));
        content.setBackground(outlined(DIALOG_BG,18,BORDER));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView emblem=label("≋",27,BLUE,true);emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(bg(0xff123352,50));
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(dp(49),dp(49));
        ep.setMargins(0,0,dp(12),0);top.addView(emblem,ep);
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("Буферизация видео",20,WHITE,true));
        TextView subtitle=label("Скорость запуска и устойчивость канала",12,SUB,false);
        subtitle.setPadding(0,dp(5),0,0);
        titles.addView(subtitle);
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(top);
        String[] symbols={"➤","⚖","◇"};
        String[] names={"Быстрый","Сбалансированный","Устойчивый"};
        String[] details={"Быстрое переключение","Оптимальный режим","Меньше зависаний"};
        String[] times={"0,5 с","2 с","4 с"};
        ArrayList<LinearLayout> choices=new ArrayList<>();
        for(int i=0;i<3;i++){
            LinearLayout choice=bufferChoice(symbols[i],names[i],details[i],times[i],i==chosen,
                                              dialog,i,pending,choices);
            choices.add(choice);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(75));
            cp.setMargins(0,dp(i==0?19:8),0,0);
            content.addView(choice,cp);
        }
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel=button("Отмена");cancel.setOnClickListener(v->dialog.dismiss());
        Button apply=button("Применить");
        apply.setBackground(blueGradient(12));apply.setTextSize(15);
        apply.setOnClickListener(v->{
            getPreferences(0).edit().putInt("buffer_ms",CACHE_OPTIONS[pending[0]]).apply();
            dialog.dismiss();
            if(viewing)showStatus("Буфер: "+CACHE_LABELS[pending[0]]+" (со следующего канала)");
        });
        LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(0,dp(51),1);
        c.setMargins(0,0,dp(5),0);actions.addView(cancel,c);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(51),1);
        ap.setMargins(dp(5),0,0,0);actions.addView(apply,ap);
        LinearLayout.LayoutParams al=new LinearLayout.LayoutParams(-1,-2);
        al.setMargins(0,dp(19),0,0);content.addView(actions,al);
        dialog.setContentView(content);dialog.show();
        Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params=w.getAttributes();params.dimAmount=0.70f;
            w.setAttributes(params);
            int width=tv?dp(520):(int)(getResources().getDisplayMetrics().widthPixels*0.94f);
            w.setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT);
        }
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
        else if(event.type==MediaPlayer.Event.TimeChanged && (!statsAvailable || lastDisplayedPictures<=0)){
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
        else if(viewing)handler.postDelayed(()->{
            if(status!=null && (getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT || !visible))
                status.setVisibility(View.GONE);
        },3600);
    }
    private void switchTo(int i){
        if(names.isEmpty())return;
        current=(i+names.size())%names.size();
        getPreferences(0).edit().putInt("last",current).apply();
        recoveryAttempts=0;
        if(phoneChannelTitle!=null)phoneChannelTitle.setText(names.get(current));
        if(phoneList!=null){phoneList.invalidateViews();phoneList.setSelection(current);}
        startPlayer();
    }
    private void showMenu(){
        if(menu!=null&&menu.isShowing())return;
        menu=new Dialog(this);menu.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(BG);
        box.addView(text("МТС IPTV  ·  "+names.size()+" каналов",22,true));
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
