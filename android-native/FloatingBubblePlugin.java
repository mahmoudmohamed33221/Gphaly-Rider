package com.mgaper.gphalyrider;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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

    private boolean canDrawOverlaysCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(getContext());
        }
        return true; // قبل أندرويد 6، الصلاحية دي كانت ممنوحة تلقائيًا وقت التثبيت
    }
}
