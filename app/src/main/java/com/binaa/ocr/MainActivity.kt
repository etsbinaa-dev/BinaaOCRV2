package com.binaa.ocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.binaa.ocr.ui.home.HomeScreen
import com.binaa.ocr.ui.theme.BinaaTheme

/** الشاشة الرئيسية: متابعة وإرشاد فقط. لا يوجد فيها أي زر لبدء قراءة أو لإضافة عملية. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BinaaTheme { HomeScreen() } }
    }
}
