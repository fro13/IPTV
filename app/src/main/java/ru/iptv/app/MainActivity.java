package ru.iptv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.SeekBar;
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
    private Button fullscreenButton;
    private boolean fullscreenRequested=false, silentRestart=false;
    private final Bitmap[] logoCache=new Bitmap[22];
    private final Runnable hideFullscreen=()->{if(fullscreenButton!=null)fullscreenButton.setVisibility(View.GONE);};
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
                String name=item.getString("name");
                if(i==20)name="БСТ";
                if(i==21)name="Башкортостан 24";
                names.add(name);urls.add(item.getString("url"));
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

    private String visibleName(int i){return names.get(i);}
    private android.graphics.drawable.Drawable tileBg(boolean selected){
        return outlined(selected?0xff11273d:0xff0b1928,12,selected?0xff2189d8:0xff263b4e);
    }
    private Bitmap channelLogo(int index){
        if(index<0 || index>=logoCache.length)return null;
        if(logoCache[index]!=null)return logoCache[index];
        try(InputStream in=getAssets().open(String.format(java.util.Locale.US,"channel_logos/%02d.png",index+1))){
            logoCache[index]=BitmapFactory.decodeStream(in);
        }catch(Exception ignored){}
        return logoCache[index];
    }
    private View gridTile(int index,int tileSize){
        FrameLayout tile=new FrameLayout(this);
        tile.setBackground(tileBg(index==current));
        tile.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,tileSize));
        ImageView logo=new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bitmap=channelLogo(index);
        if(bitmap!=null)logo.setImageBitmap(bitmap);
        FrameLayout.LayoutParams imageParams=new FrameLayout.LayoutParams(-1,-1);
        imageParams.setMargins(dp(6),dp(15),dp(6),dp(5));
        tile.addView(logo,imageParams);
        TextView number=label(String.valueOf(index+1),12,WHITE,false);
        number.setGravity(Gravity.CENTER);number.setBackground(bg(0xff1a3652,8));
        FrameLayout.LayoutParams numberParams=new FrameLayout.LayoutParams(dp(29),dp(27),Gravity.TOP|Gravity.LEFT);
        numberParams.setMargins(dp(4),dp(4),0,0);
        tile.addView(number,numberParams);
        tile.setContentDescription((index+1)+". "+visibleName(index));
        return tile;
    }
    private ArrayAdapter<String> adapter(){
        ArrayList<String> labels=new ArrayList<>();
        for(int i=0;i<names.size();i++)labels.add(channelLabel(i));
        return new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View convert,ViewGroup parent){
                TextView row=text(labels.get(position),tv?19:16,position==current);
                row.setMinHeight(dp(54));row.setBackground(tileBg(position==current));
                return row;
            }
        };
    }
    private LinearLayout navigationBar(){
        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(7),dp(3),dp(7),dp(5));
        bar.setBackgroundColor(BG);
        Button previous=button("◀  Предыдущий");
        Button list=button("☷  Список");
        Button next=button("Следующий  ▶");
        previous.setTextSize(11);list.setTextSize(13);next.setTextSize(11);
        list.setBackground(outlined(0xff163b67,11,0xff396f9b));
        previous.setOnClickListener(v->switchTo(current-1));
        next.setOnClickListener(v->switchTo(current+1));
        list.setOnClickListener(v->showBrowser());
        Button[] buttons={previous,list,next};
        float[] weights={1.1f,1f,1.1f};
        for(int i=0;i<buttons.length;i++){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),weights[i]);
            lp.setMargins(dp(3),0,dp(3),0);
            bar.addView(buttons[i],lp);
        }
        return bar;
    }
    private void showBrowser(){
        viewing=false;fullscreenRequested=false;
        handler.removeCallbacks(hideFullscreen);
        releasePlayer();
        frame=null;surface=null;status=null;controls=null;phoneRoot=null;phonePage=null;
        phoneChannelTitle=null;fullscreenButton=null;
        if(!tv){
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        }
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("МТС IPTV",27,true);
        title.setPadding(dp(18),dp(10),0,dp(10));
        header.addView(title,new LinearLayout.LayoutParams(0,dp(65),1));
        Button gear=button("⚙");gear.setTextSize(22);
        gear.setContentDescription("Буферизация потока");gear.setOnClickListener(v->showBufferSettings());
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(48),dp(46));
        gp.setMargins(0,dp(8),dp(13),dp(8));header.addView(gear,gp);
        root.addView(header);
        final int screenWidth=getResources().getDisplayMetrics().widthPixels;
        final int tileSize=(screenWidth-dp(28)-dp(14))/3;
        GridView grid=new GridView(this);
        grid.setNumColumns(3);
        grid.setColumnWidth(tileSize);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(7));grid.setVerticalSpacing(dp(7));
        grid.setPadding(dp(14),dp(7),dp(14),dp(15));
        grid.setClipToPadding(false);grid.setVerticalScrollBarEnabled(false);
        grid.setBackgroundColor(BG);
        grid.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return names.size();}
            @Override public Object getItem(int position){return names.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View old,ViewGroup parent){
                return gridTile(position,tileSize);
            }
        });
        grid.setOnItemClickListener((parent,v,pos,id)->{
            current=pos;recoveryAttempts=0;showPlayer();
        });
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private void revealFullscreen(){
        if(fullscreenButton==null || tv)return;
        fullscreenButton.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideFullscreen);
        handler.postDelayed(hideFullscreen,3500);
    }
    private void toggleFullscreen(){
        if(tv)return;
        boolean goingLandscape=!fullscreenRequested;
        fullscreenRequested=goingLandscape;
        setRequestedOrientation(goingLandscape?
             ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        revealFullscreen();
    }
    private void showPlayer(){
        viewing=true;visible=true;
        if(tv)getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
        surface=new VLCVideoLayout(this);
        frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        status=label("",13,WHITE,true);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(10),dp(7),dp(10),dp(7));
        status.setBackground(bg(0xe916273b,9));
        FrameLayout.LayoutParams stParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        stParams.setMargins(dp(9),dp(9),dp(9),dp(9));
        frame.addView(status,stParams);
        status.setVisibility(View.GONE);
        if(!tv){
            getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
            phoneRoot=new FrameLayout(this);phoneRoot.setBackgroundColor(BG);
            phonePage=new LinearLayout(this);phonePage.setOrientation(LinearLayout.VERTICAL);
            phonePage.setBackgroundColor(BG);
            LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
            Button back=button("‹");back.setTextSize(30);
            back.setOnClickListener(v->showBrowser());
            LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(dp(45),dp(46));
            backParams.setMargins(dp(12),0,dp(7),0);
            top.addView(back,backParams);
            TextView heading=label("МТС IPTV",23,WHITE,true);
            top.addView(heading,new LinearLayout.LayoutParams(0,dp(61),1));
            Button settings=button("⚙");settings.setTextSize(22);
            settings.setOnClickListener(v->showBufferSettings());
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(47),dp(46));
            sp.setMargins(dp(4),0,dp(12),0);top.addView(settings,sp);
            phonePage.addView(top);
            phonePage.addView(new View(this),new LinearLayout.LayoutParams(1,0,1.1f));
            videoHost=new FrameLayout(this);videoHost.setBackgroundColor(Color.BLACK);
            int videoHeight=(int)((getResources().getDisplayMetrics().widthPixels-dp(24))*9.0/16.0);
            LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,videoHeight);
            vp.setMargins(dp(12),0,dp(12),0);
            phonePage.addView(videoHost,vp);
            phoneChannelTitle=label(visibleName(current),22,WHITE,true);
            phoneChannelTitle.setGravity(Gravity.CENTER);
            phonePage.addView(phoneChannelTitle,new LinearLayout.LayoutParams(-1,dp(58)));
            LinearLayout volumeRow=new LinearLayout(this);
            volumeRow.setGravity(Gravity.CENTER_VERTICAL);
            volumeRow.setPadding(dp(35),0,dp(35),0);
            TextView volumeIcon=label("◖))",19,SUB,false);
            volumeRow.addView(volumeIcon);
            SeekBar volume=new SeekBar(this);
            volume.setMax(100);volume.setProgress(75);
            volume.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff5388bf));
            volume.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffb5cee8));
            volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                    if(fromUser && player!=null)player.setVolume(progress);
                    if(fromUser)getPreferences(0).edit().putInt("volume",progress).apply();
                }
                public void onStartTrackingTouch(SeekBar bar){}
                public void onStopTrackingTouch(SeekBar bar){}
            });
            volume.setProgress(getPreferences(0).getInt("volume",75));
            volumeRow.addView(volume,new LinearLayout.LayoutParams(0,dp(42),1));
            phonePage.addView(volumeRow);
            phonePage.addView(new View(this),new LinearLayout.LayoutParams(1,0,1f));
            controls=navigationBar();
            phoneControlsHost=new FrameLayout(this);
            phoneControlsHost.addView(controls);
            phonePage.addView(phoneControlsHost,new LinearLayout.LayoutParams(-1,dp(58)));
            fullscreenButton=button("⛶");
            fullscreenButton.setTextSize(28);fullscreenButton.setContentDescription("На весь экран");
            fullscreenButton.setBackground(bg(0xdd102438,9));
            fullscreenButton.setOnClickListener(v->toggleFullscreen());
            FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(45),dp(45),Gravity.TOP|Gravity.RIGHT);
            fp.setMargins(0,dp(10),dp(10),0);frame.addView(fullscreenButton,fp);
            frame.setOnClickListener(v->revealFullscreen());
            surface.setOnClickListener(v->revealFullscreen());
            setContentView(phoneRoot);
            applyPhoneOrientation(getResources().getConfiguration());
            revealFullscreen();
        }else setContentView(frame);
        getPreferences(0).edit().putInt("last",current).apply();
        startPlayer();
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        applyPhoneOrientation(config);
    }
    private void removeParent(View view){
        if(view!=null && view.getParent() instanceof ViewGroup)
            ((ViewGroup)view.getParent()).removeView(view);
    }
    private void applyPhoneOrientation(Configuration config){
        if(tv || !viewing || phoneRoot==null || frame==null)return;
        boolean landscape=config.orientation==Configuration.ORIENTATION_LANDSCAPE;
        removeParent(frame);removeParent(controls);
        phoneRoot.removeAllViews();
        if(landscape){
            fullscreenRequested=true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            phoneRoot.addView(frame,new FrameLayout.LayoutParams(-1,-1));
            if(fullscreenButton!=null)fullscreenButton.setText("⊟");
            visible=false;
        }else{
            fullscreenRequested=false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            videoHost.addView(frame,new FrameLayout.LayoutParams(-1,-1));
            phoneControlsHost.addView(controls,new FrameLayout.LayoutParams(-1,-2));
            phoneRoot.addView(phonePage,new FrameLayout.LayoutParams(-1,-1));
            if(fullscreenButton!=null)fullscreenButton.setText("⛶");
            visible=true;
        }
        if(status!=null)status.setVisibility(View.GONE);
        revealFullscreen();
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


    private final class BufferIcon extends View{
        private final int kind;
        BufferIcon(int index){super(MainActivity.this);kind=index;}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float x=getWidth()/2f,y=getHeight()/2f;
            float scale=dp(1);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xff9bbfdf);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2.1f));
            p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            Path path=new Path();
            if(kind==0){
                p.setStyle(Paint.Style.FILL);
                float[] dummy=new float[0];
                path.moveTo(x+1*scale,y-16*scale);
                path.lineTo(x-11*scale,y+2*scale);
                path.lineTo(x-2*scale,y+2*scale);
                path.lineTo(x-5*scale,y+16*scale);
                path.lineTo(x+12*scale,y-4*scale);
                path.lineTo(x+3*scale,y-4*scale);
                path.close();
                canvas.drawPath(path,p);
            }else if(kind==1){
                canvas.drawLine(x,y-15*scale,x,y+12*scale,p);
                canvas.drawLine(x-12*scale,y-10*scale,x+12*scale,y-10*scale,p);
                canvas.drawLine(x-9*scale,y+13*scale,x+9*scale,y+13*scale,p);
                canvas.drawLine(x-8*scale,y-10*scale,x-14*scale,y+2*scale,p);
                canvas.drawLine(x-8*scale,y-10*scale,x-2*scale,y+2*scale,p);
                canvas.drawLine(x+8*scale,y-10*scale,x+2*scale,y+2*scale,p);
                canvas.drawLine(x+8*scale,y-10*scale,x+14*scale,y+2*scale,p);
                canvas.drawArc(x-16*scale,y-2*scale,x,y+8*scale,0,180,false,p);
                canvas.drawArc(x,y-2*scale,x+16*scale,y+8*scale,0,180,false,p);
            }else{
                path.moveTo(x,y-16*scale);path.lineTo(x+13*scale,y-11*scale);
                path.lineTo(x+11*scale,y+4*scale);
                path.quadTo(x+8*scale,y+12*scale,x,y+17*scale);
                path.quadTo(x-8*scale,y+12*scale,x-11*scale,y+4*scale);
                path.lineTo(x-13*scale,y-11*scale);path.close();
                canvas.drawPath(path,p);
            }
        }
    }
    private LinearLayout modeRow(int index,boolean active){
        String[] names={"Быстрый","Сбалансированный","Устойчивый"};
        String[] subtitles={"Быстрое переключение","Оптимальный режим","Для нестабильной сети"};
        String[] times={"0,5 с","2 с","4 с"};
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(4),dp(7),dp(4));
        row.setBackground(outlined(active?0xff20374e:0xff152638,11,active?0xff4b7796:0xff2a3e52));
        BufferIcon icon=new BufferIcon(index);
        icon.setBackground(bg(0xff1c334a,9));
        row.addView(icon,new LinearLayout.LayoutParams(dp(41),dp(46)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(label(names[index],15,WHITE,true));
        copy.addView(label(subtitles[index],12,SUB,false));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);
        cp.setMargins(dp(12),0,0,0);
        row.addView(copy,cp);
        TextView amount=label(times[index],13,active?0xffb7d7f4:WHITE,false);
        amount.setGravity(Gravity.CENTER);
        row.addView(amount,new LinearLayout.LayoutParams(dp(42),dp(46)));
        TextView select=label(active?"◉":"○",23,active?0xff83b9e8:0xff71869c,false);
        select.setGravity(Gravity.CENTER);
        row.addView(select,new LinearLayout.LayoutParams(dp(29),dp(46)));
        return row;
    }
    private void showBufferSettings(){
        int currentMode=1;
        for(int i=0;i<CACHE_OPTIONS.length;i++)if(cacheMs()==CACHE_OPTIONS[i])currentMode=i;
        int[] pending={currentMode};
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16),dp(20),dp(16),dp(16));
        content.setBackground(outlined(0xff101e2e,18,0xff2e4052));
        TextView title=label("Буферизация потока",20,WHITE,true);
        title.setGravity(Gravity.CENTER);
        content.addView(title,new LinearLayout.LayoutParams(-1,dp(32)));
        TextView detail=label("Скорость запуска и стабильность IPTV",12,SUB,false);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(-1,dp(35));
        dp1.setMargins(0,0,0,dp(8));
        content.addView(detail,dp1);
        ArrayList<LinearLayout> modes=new ArrayList<>();
        for(int i=0;i<3;i++){
            final int which=i;
            LinearLayout row=modeRow(i,pending[0]==i);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(76));
            rp.setMargins(0,0,0,dp(8));
            content.addView(row,rp);modes.add(row);
            row.setOnClickListener(v->{
                pending[0]=which;
                for(int j=0;j<modes.size();j++){
                    boolean selected=j==which;
                    LinearLayout option=modes.get(j);
                    option.setBackground(outlined(selected?0xff20374e:0xff152638,11,selected?0xff4b7796:0xff2a3e52));
                    TextView amount=(TextView)option.getChildAt(2);
                    TextView mark=(TextView)option.getChildAt(3);
                    amount.setTextColor(selected?0xffb7d7f4:WHITE);
                    mark.setText(selected?"◉":"○");
                    mark.setTextColor(selected?0xff83b9e8:0xff71869c);
                }
            });
        }
        LinearLayout actions=new LinearLayout(this);
        Button cancel=button("Отмена");
        cancel.setOnClickListener(v->dialog.dismiss());
        Button apply=button("Применить");
        apply.setTextSize(14);apply.setBackground(bg(0xff315576,11));
        apply.setOnClickListener(v->{
            int chosen=CACHE_OPTIONS[pending[0]];
            boolean changed=chosen!=cacheMs();
            getPreferences(0).edit().putInt("buffer_ms",chosen).apply();
            dialog.dismiss();
            if(changed && viewing){
                recoveryAttempts=0;
                silentRestart=true;
                startPlayer();
            }
        });
        LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(0,dp(47),1);
        cancelLp.setMargins(0,dp(8),dp(5),0);actions.addView(cancel,cancelLp);
        LinearLayout.LayoutParams applyLp=new LinearLayout.LayoutParams(0,dp(47),1);
        applyLp.setMargins(dp(5),dp(8),0,0);actions.addView(apply,applyLp);
        content.addView(actions);
        dialog.setContentView(content);
        dialog.show();
        Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params=w.getAttributes();params.dimAmount=.63f;
            w.setAttributes(params);
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(24),dp(440)),
                        WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
    private void onPlayerEvent(MediaPlayer p,MediaPlayer.Event event){
        if(p!=player || !viewing)return;
        if(event.type==MediaPlayer.Event.Playing){
            playingEventSeen=true;
            playbackError=false;
            if(status!=null)status.setVisibility(View.GONE);
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
            if(silentRestart){
                if(status!=null)status.setVisibility(View.GONE);
                silentRestart=false;
            }else showStatus("Подключение: "+channelLabel(current));
            player.play();
            if(!tv)player.setVolume(getPreferences(0).getInt("volume",75));
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
        if(phoneChannelTitle!=null)phoneChannelTitle.setText(visibleName(current));
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
        if(!tv&&viewing){
            if(fullscreenRequested || getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE){
                fullscreenRequested=false;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }else showBrowser();
            return;
        }
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
