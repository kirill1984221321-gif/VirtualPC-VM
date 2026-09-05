package com.virtualpcvm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object VmRepository {
    private const val PREFS_NAME = "virtualpcvm_prefs"
    private const val KEY_VMS = "vms_list"

    fun getAllVms(context: Context): MutableList<VmConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_VMS, null) ?: return mutableListOf()
        val list = mutableListOf<VmConfig>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    VmConfig(
                        id = obj.optLong("id", System.currentTimeMillis() + i),
                        name = obj.optString("name", "VM"),
                        architecture = try {
                            Architecture.valueOf(obj.optString("arch", Architecture.X86_64.name))
                        } catch (_: Exception) { Architecture.X86_64 },
                        machineType = try {
                            MachineType.valueOf(obj.optString("machine", MachineType.PC.name))
                        } catch (_: Exception) { MachineType.PC },
                        ramMb = obj.optInt("ram", 1024),
                        cpuCores = obj.optInt("cpu", 2),
                        cpuModel = obj.optString("cpuModel", "max"),
                        diskPath = obj.optString("disk", ""),
                        diskFormat = obj.optString("diskFormat", "qcow2"),
                        isoPath = obj.optString("iso", ""),
                        bootDevice = obj.optString("bootDevice", "disk"),
                        vgaDriver = obj.optString("vgaDriver", "std"),
                        networkMode = obj.optString("networkMode", "user"),
                        networkAdapter = obj.optString("networkAdapter", "rtl8139"),
                        inputDevice = obj.optString("inputDevice", "usb-tablet"),
                        diskSizeGb = obj.optInt("diskSizeGb", 10),
                        enableAudio = obj.optBoolean("audio", true),
                        audioModel = obj.optString("audioModel", "hda"),
                        enableUsbTablet = obj.optBoolean("usbTablet", true),
                        enableMtcg = obj.optBoolean("mtcg", true),
                        enableKvm = obj.optBoolean("kvm", false),
                        extraArgs = obj.optString("extraArgs", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun getVm(context: Context, vmId: Long): VmConfig? {
        return getAllVms(context).find { it.id == vmId } ?: QemuManager.getVmConfig(vmId)
    }

    fun saveAllVms(context: Context, list: List<VmConfig>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (cfg in list) {
            val obj = JSONObject().apply {
                put("id", cfg.id)
                put("name", cfg.name)
                put("arch", cfg.architecture.name)
                put("machine", cfg.machineType.name)
                put("ram", cfg.ramMb)
                put("cpu", cfg.cpuCores)
                put("cpuModel", cfg.cpuModel)
                put("disk", cfg.diskPath)
                put("diskFormat", cfg.diskFormat)
                put("iso", cfg.isoPath)
                put("bootDevice", cfg.bootDevice)
                put("vgaDriver", cfg.vgaDriver)
                put("networkMode", cfg.networkMode)
                put("networkAdapter", cfg.networkAdapter)
                put("inputDevice", cfg.inputDevice)
                put("diskSizeGb", cfg.diskSizeGb)
                put("audio", cfg.enableAudio)
                put("audioModel", cfg.audioModel)
                put("usbTablet", cfg.enableUsbTablet)
                put("mtcg", cfg.enableMtcg)
                put("kvm", cfg.enableKvm)
                put("extraArgs", cfg.extraArgs)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_VMS, arr.toString()).apply()
    }
}
