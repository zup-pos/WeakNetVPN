package com.weaktool.vpn.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.weaktool.vpn.R
import com.weaktool.vpn.config.VpnConfig
import com.weaktool.vpn.core.vpn.WeakNetVpnService
import com.weaktool.vpn.notification.NotificationHelper
import com.weaktool.vpn.perm.PermissionFlow
import com.weaktool.vpn.qos.NetProfile

/**
 * 主界面 —— 权限引导 + 启动/停止 + QoS 参数录入。
 *
 * 本工程只重写 VPN 隧道核心引擎；完整控制面板(悬浮窗)复用既有 QNET 资源，
 * 不在本 Activity 内复刻。此处仅提供一套最小可用的开关界面用于联调引擎。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvOverlayHint: TextView
    private lateinit var etBandwidth: EditText
    private lateinit var etDelay: EditText
    private lateinit var etJitter: EditText
    private lateinit var etLoss: EditText
    private lateinit var btnToggle: Button

    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        tvOverlayHint = findViewById(R.id.tv_overlay_hint)
        etBandwidth = findViewById(R.id.et_bandwidth)
        etDelay = findViewById(R.id.et_delay)
        etJitter = findViewById(R.id.et_jitter)
        etLoss = findViewById(R.id.et_loss)
        btnToggle = findViewById(R.id.btn_toggle)

        btnToggle.setOnClickListener {
            if (running) stopVpn() else startVpnFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        running = VpnConfig.isActive(this)
        syncUi()
        // Android 14+ 悬浮窗权限需在系统设置引导，权限回来时刷新提示。
        if (PermissionFlow.canDrawOverlays(this)) {
            tvOverlayHint.visibility = TextView.GONE
        } else {
            tvOverlayHint.visibility = TextView.VISIBLE
        }
    }

    /** 启动前的权限编排：通知权限(Android13+) → VPN 授权 → 悬浮窗引导。 */
    private fun startVpnFlow() {
        PermissionFlow.requestNotification(this)
        // VPN 隧道建立依赖用户同意；未准备就绪时弹出系统授权页。
        if (!PermissionFlow.hasVpnPermission(this)) {
            PermissionFlow.requestVpn(this, PermissionFlow.REQ_VPN)
            return
        }
        // 悬浮窗控制面板仅作引导，不阻塞隧道启动。
        if (!PermissionFlow.canDrawOverlays(this)) {
            PermissionFlow.requestOverlay(this)
        }
        launchService()
    }

    private fun launchService() {
        persistProfile()
        val intent = Intent(this, WeakNetVpnService::class.java)
            .setAction(WeakNetVpnService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        running = true
        syncUi()
    }

    private fun stopVpn() {
        val intent = Intent(this, WeakNetVpnService::class.java)
            .setAction(WeakNetVpnService.ACTION_STOP)
        startService(intent)
        running = false
        VpnConfig.save(this, readProfile(), false)
        syncUi()
    }

    private fun persistProfile() {
        VpnConfig.save(this, readProfile(), running || !VpnConfig.isActive(this))
    }

    private fun readProfile(): NetProfile {
        fun num(v: String, d: Int): Int = v.trim().toIntOrNull() ?: d
        return NetProfile(
            bandwidthKbps = num(etBandwidth.text.toString(), 0),
            delayMs = num(etDelay.text.toString(), 0),
            jitterMs = num(etJitter.text.toString(), 0),
            lossPercent = num(etLoss.text.toString(), 0)
        )
    }

    private fun syncUi() {
        tvStatus.text = getString(if (running) R.string.status_on else R.string.status_off)
        btnToggle.text = getString(if (running) R.string.stop_vpn else R.string.start_vpn)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PermissionFlow.REQ_VPN -> {
                if (PermissionFlow.hasVpnPermission(this)) {
                    launchService()
                } else {
                    Toast.makeText(this, R.string.notif_text_vpn, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionFlow.REQ_NOTIF &&
            !PermissionFlow.hasNotificationPermission(this)
        ) {
            // 通知权限缺失只影响前台服务通知展示，不阻断隧道；提示即可。
            Toast.makeText(this, "通知权限未授予", Toast.LENGTH_SHORT).show()
        }
    }

    // 供 Activity 快捷处理，避免 lint 未用警告。
    @Suppress("unused")
    private fun ensureChannelsCompat() {
        NotificationHelper.ensureChannels(this)
    }

    companion object {
        // 供测试/悬浮窗直接拉起引擎。
        fun createStartIntent(activity: Activity): Intent =
            Intent(activity, WeakNetVpnService::class.java)
                .setAction(WeakNetVpnService.ACTION_START)
    }
}
