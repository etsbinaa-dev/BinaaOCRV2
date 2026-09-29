package com.binaa.ocr.ui

import android.app.Activity
import android.view.WindowManager

/**
 * كل شاشات V2 تعرض (أو ستعرض) بيانات مالية وصور إيصالات:
 * لا لقطات شاشة للتطبيق، ولا معاينة في قائمة التطبيقات الأخيرة. المشاركة تتم فقط عبر خياراتها الصريحة.
 */
fun Activity.secureWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
}
