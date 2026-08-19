package com.mgaper.gphalyrider;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.graphics.drawable.GradientDrawable;

// =========================================================================
// 🆕 خدمة الأيقونة العائمة (زي أيقونة Talabat Rider / Messenger Chat Heads بالظبط):
// بترسم أيقونة دايرية فوق أي برنامج تاني مفتوح، وبمجرد ما المندوب يدوس عليها
// بترجّعه لبرنامج جبهالي ريدر فورًا (نفس النسخة الشغالة، مش بيتفتح من جديد -
// عشان كده الـ Activity متسجلة "singleTask" في الـ Manifest). الخدمة دي بتشتغل
// كـ Foreground Service (بإشعار ثابت بسيط) عشان تفضل شغالة وميتقفلش من نظام
// التشغيل حتى لو المندوب فتح برامج تانية كتير أو الذاكرة زحمت.
// =========================================================================
public class FloatingBubbleService extends Service {

    private WindowManager windowManager;
    private FrameLayout bubbleView;
    private WindowManager.LayoutParams params;
    private static final String CHANNEL_ID = "gphaly_rider_bubble_channel";
    private static final int NOTIF_ID = 991177;

    // =========================================================================
    // 🆕 نبضة موقع زمنية ثابتة (زي أغنية شغالة برضو والشاشة مقفولة): بغض النظر
    // عن حركة المندوب، كل 15 ثانية بنطلب قراءة GPS جديدة ونبعتها لـ JS مباشرة.
    // ده أهم من الاعتماد بس على "المسافة اتغيرت" (اللي كانت بتقف تمامًا لو
    // المندوب واقف ساكن فترة طويلة) - النبضة دي شغالة سواء الشاشة مقفولة، أو
    // المندوب خارج من البرنامج، أو فاتح برنامج تاني، طول ما الخدمة شغالة
    // =========================================================================
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;
    private LocationManager locationManager;
    private static final long HEARTBEAT_INTERVAL_MS = 15000;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceCompat();
        showBubble();
        startLocationHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 🆕 START_STICKY: لو نظام التشغيل قفل الخدمة عشان الذاكرة، أندرويد
        // هيحاول يرجّعها تشتغل تاني لوحده أول ما الموارد تتوفر
        return START_STICKY;
    }

    // 🆕 حلقة النبضة: كل 15 ثانية نطلب قراءة موقع واحدة (مش تتبع مستمر - أخف على
    // البطارية) عبر LocationManager القياسي في أندرويد نفسه (من غير أي مكتبة
    // خارجية إضافية، عشان نتجنب أي مشكلة بناء غير متوقعة)
    private void startLocationHeartbeat() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        heartbeatHandler = new Handler(Looper.getMainLooper());
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                requestSingleLocationFix();
                if (heartbeatHandler != null) heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        };
        heartbeatHandler.postDelayed(heartbeatRunnable, 2000); // أول نبضة بعد ثانيتين من بدء الخدمة
    }

    private void requestSingleLocationFix() {
        if (locationManager == null) return;
        boolean hasPermission = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasPermission) return;
        try {
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) return;
                    FloatingBubblePlugin.emitHeartbeat(location.getLatitude(), location.getLongitude(), location.getAccuracy());
                    // 🆕 طلب واحد بس في كل نبضة - نوقف الاستماع فورًا عشان توفير البطارية
                    try { locationManager.removeUpdates(this); } catch (Exception e) {}
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };
            String provider = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER
                    : LocationManager.NETWORK_PROVIDER;
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
        } catch (Exception e) {
            // 🆕 لو حصل أي خطأ (GPS مقفول لحظيًا مثلًا)، منسيبش الخدمة توقف -
            // النبضة الجاية بعد 15 ثانية هتحاول تاني
        }
    }

    private void stopLocationHeartbeat() {
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        }
        heartbeatHandler = null;
        heartbeatRunnable = null;
    }

    private void startForegroundServiceCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "جبهالي ريدر - شغال", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("جبهالي ريدر شغال")
                .setContentText("التطبيق شغال في الخلفية - دوس هنا أو على الأيقونة العائمة للرجوع")
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void showBubble() {
        if (bubbleView != null) return; // شغالة بالفعل
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int size = dp(56);
        bubbleView = new FrameLayout(this);
        bubbleView.setLayoutParams(new FrameLayout.LayoutParams(size, size));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#0EA5A5")); // 🆕 لون التطبيق الأساسي (تركواز/سيان)
        bg.setStroke(dp(2), Color.WHITE);
        bubbleView.setBackground(bg);
        bubbleView.setElevation(dp(6));

        ImageView icon = new ImageView(this);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams((int) (size * 0.6), (int) (size * 0.6));
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        try { icon.setImageResource(getApplicationInfo().icon); } catch (Exception e) {}
        bubbleView.addView(icon);

        int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                size, size, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        // 🆕 مكانها الافتراضي: الشمال، في نص الشاشة رأسيًا - زي ما طلب بالظبط
        params.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        params.x = dp(6);
        params.y = dm.heightPixels / 2 - (size / 2);

        final float[] touchStart = new float[2];
        final int[] paramsStart = new int[2];
        final boolean[] isDragging = {false};

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStart[0] = event.getRawX();
                        touchStart[1] = event.getRawY();
                        paramsStart[0] = params.x;
                        paramsStart[1] = params.y;
                        isDragging[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchStart[0];
                        float dy = event.getRawY() - touchStart[1];
                        // 🆕 لو المندوب حرّك إصبعه فعليًا (مش مجرد دوسة)، نعتبرها سحب للأيقونة
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) isDragging[0] = true;
                        if (isDragging[0]) {
                            params.x = paramsStart[0] + (int) dx;
                            params.y = paramsStart[1] + (int) dy;
                            try { windowManager.updateViewLayout(bubbleView, params); } catch (Exception e) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging[0]) {
                            // 🆕 دوسة عادية (مش سحب) = ارجع لبرنامج جبهالي ريدر فورًا
                            openApp();
                        }
                        return true;
                }
                return false;
            }
        });

        try { windowManager.addView(bubbleView, params); } catch (Exception e) {
            // 🆕 لو حصل أي خطأ (مثلاً الإذن اتشال فجأة)، نوقف الخدمة بدل ما تفضل معلّقة
            stopSelf();
        }
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopLocationHeartbeat();
        if (bubbleView != null && windowManager != null) {
            try { windowManager.removeView(bubbleView); } catch (Exception e) {}
            bubbleView = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
