package com.mgaper.gphalyrider;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import androidx.activity.result.ActivityResult;

// =========================================================================
// 🆕 الجسر بين JavaScript والخدمة الأصلية للأيقونة العائمة. صلاحية "الرسم فوق
// التطبيقات الأخرى" (SYSTEM_ALERT_WINDOW) صلاحية خاصة في أندرويد، مينفعش
// تتطلب بنافذة الأذونات العادية - لازم المستخدم يوافق عليها من شاشة نظام خاصة.
// =========================================================================
@CapacitorPlugin(name = "FloatingBubble")
public class FloatingBubblePlugin extends Plugin {

    // 🆕 مرجع ثابت للبلجن الشغال حاليًا - عشان الخدمة (FloatingBubbleService)
    // تقدر توصل له وتبعث منه حدث لـ JS حتى لو هي كلاس منفصل تمامًا عن البلجن
    private static FloatingBubblePlugin activeInstance;

    @Override
    public void load() {
        super.load();
        activeInstance = this;
    }

    // 🆕 بتتنادى من FloatingBubbleService كل ما توصلها قراءة موقع جديدة من
    // النبضة الزمنية الدورية (مش بس لما المسافة تتغير) - وبتبعتها لـ JS عن
    // طريق نظام أحداث Capacitor العادي (نفس آلية إشعارات الأزرار الأصلية)،
    // وده اللي بيضمن إن التحديث يوصل حتى والشاشة مقفولة أو التطبيق في الخلفية
    public static void emitHeartbeat(double lat, double lng, float accuracy) {
        if (activeInstance == null) return;
        JSObject data = new JSObject();
        data.put("lat", lat);
        data.put("lng", lng);
        data.put("accuracy", accuracy);
        activeInstance.notifyListeners("locationHeartbeat", data);
    }

    @PluginMethod
    public void checkOverlayPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", canDrawOverlaysCompat());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestOverlayPermission(PluginCall call) {
        if (canDrawOverlaysCompat()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
        startActivityForResult(call, intent, "overlayPermissionResult");
    }

    @ActivityCallback
    private void overlayPermissionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSObject ret = new JSObject();
        ret.put("granted", canDrawOverlaysCompat());
        call.resolve(ret);
    }

    @PluginMethod
    public void startBubble(PluginCall call) {
        if (!canDrawOverlaysCompat()) {
            call.reject("OVERLAY_PERMISSION_DENIED");
            return;
        }
        Intent svc = new Intent(getContext(), FloatingBubbleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(svc);
        } else {
            getContext().startService(svc);
        }
        call.resolve();
    }

    @PluginMethod
    public void stopBubble(PluginCall call) {
        Intent svc = new Intent(getContext(), FloatingBubbleService.class);
        getContext().stopService(svc);
        call.resolve();
    }

    // 🆕 بيتحكم في ظهور دائرة الأيقونة العائمة نفسها بس (تظهر/تختفي) من غير ما
    // يوقف الخدمة نفسها ولا الإشعار الثابت ولا نبضة الموقع - عشان الخدمة تفضل
    // شغالة "دايمًا" (زي مشغل الأغاني) والدائرة بس هي اللي بتتخفى وانت جوه
    // التطبيق وتظهر وانت برّاه
    @PluginMethod
    public void setBubbleVisible(PluginCall call) {
        boolean visible = call.getBoolean("visible", Boolean.TRUE);
        Intent svc = new Intent(getContext(), FloatingBubbleService.class);
        svc.setAction(visible ? FloatingBubbleService.ACTION_SHOW_BUBBLE : FloatingBubbleService.ACTION_HIDE_BUBBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(svc);
        } else {
            getContext().startService(svc);
        }
        call.resolve();
    }

    // 🆕 استثناء توفير البطارية (Battery Optimization) - أساسي عشان الخدمة تفضل
    // شغالة فعليًا في الخلفية لفترات طويلة من غير ما نظام التشغيل يجمّدها أو
    // يقفلها بحجة توفير البطارية (خصوصًا في هواتف شاومي/هواوي/سامسونج اللي
    // بتكون عدوانية جدًا في إدارة البطارية)
    @PluginMethod
    public void checkBatteryOptimizationIgnored(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", isIgnoringBatteryOptimizationsCompat());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestIgnoreBatteryOptimizations(PluginCall call) {
        if (isIgnoringBatteryOptimizationsCompat()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName()));
            startActivityForResult(call, intent, "batteryOptimizationResult");
        } catch (Exception e) {
            // 🆕 بعض هواتف الشركات المصنّعة (شاومي مثلًا) بتحجب الـ Intent المباشر ده -
            // كحل بديل نفتح شاشة إعدادات التطبيق العادية عشان الطيار يقدر يوصل
            // لخيار البطارية بنفسه من هناك
            openAppPermissionSettings(call);
        }
    }

    @ActivityCallback
    private void batteryOptimizationResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSObject ret = new JSObject();
        ret.put("granted", isIgnoringBatteryOptimizationsCompat());
        call.resolve(ret);
    }

    private boolean isIgnoringBatteryOptimizationsCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        }
        return true; // قبل أندرويد 6 مفيش مفهوم "تحسين البطارية" ده أصلًا
    }

    // 🆕 يفتح شاشة إعدادات إشعارات التطبيق مباشرة (لو اتفعّلت "عدم الإزعاج" لإشعاراتنا تحديدًا)
    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        try {
            Intent intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            intent.putExtra("android.provider.extra.APP_PACKAGE", getContext().getPackageName());
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            openAppPermissionSettings(call);
        }
    }

    // 🆕 يفتح شاشة تفاصيل صلاحيات التطبيق في إعدادات الهاتف مباشرة (لصلاحية الموقع
    // العادية اللي المستخدم رفضها ومطلوب منه يوافق عليها يدويًا)
    @PluginMethod
    public void openAppPermissionSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("CANNOT_OPEN_SETTINGS");
        }
    }

    // 🆕 يفتح شاشة إعدادات مصدر الموقع (تفعيل GPS) في نظام أندرويد مباشرة
    @PluginMethod
    public void openLocationSourceSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("CANNOT_OPEN_SETTINGS");
        }
    }

    private boolean canDrawOverlaysCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(getContext());
        }
        return true; // قبل أندرويد 6، الصلاحية دي كانت ممنوحة تلقائيًا وقت التثبيت
    }
}
