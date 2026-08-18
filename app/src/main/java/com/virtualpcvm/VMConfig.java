package com.virtualpcvm;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Independent VM configuration schema. */
public final class VMConfig {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    @SerializedName("schemaVersion") public int schemaVersion = CURRENT_SCHEMA_VERSION;
    @SerializedName("id") public String id = UUID.randomUUID().toString();
    @SerializedName("name") public String name = "Новая виртуальная машина";
    @SerializedName("osType") public String osType = "Other";
    @SerializedName("machine") public String machine = "pc";
    @SerializedName("cpu") public String cpu = "qemu64";
    @SerializedName("cpuCount") public int cpuCount = 1;
    @SerializedName("ramMb") public int ramMb = 512;
    @SerializedName("hdd") public String hdd = "";
    @SerializedName("iso") public String iso = "";
    @SerializedName("cdrom") public String cdrom = "";
    @SerializedName("bootOrder") public String bootOrder = "c";
    @SerializedName("display") public String display = "vnc";
    @SerializedName("vncPort") public int vncPort = 5901;
    @SerializedName("video") public String video = "std";
    @SerializedName("network") public String network = "user";
    @SerializedName("extraArguments") public List<String> extraArguments = new ArrayList<>();

    public VMConfig copy() {
        VMConfig c = new VMConfig();
        c.schemaVersion = schemaVersion; c.id = id; c.name = name; c.osType = osType;
        c.machine = machine; c.cpu = cpu; c.cpuCount = cpuCount; c.ramMb = ramMb;
        c.hdd = hdd; c.iso = iso; c.cdrom = cdrom; c.bootOrder = bootOrder;
        c.display = display; c.vncPort = vncPort; c.video = video; c.network = network;
        c.extraArguments = new ArrayList<>(extraArguments == null ? Collections.<String>emptyList() : extraArguments);
        return c;
    }

    public void validate() {
        if (isBlank(id)) throw new IllegalArgumentException("VM id is empty");
        if (isBlank(name)) throw new IllegalArgumentException("VM name is empty");
        if (isBlank(machine)) throw new IllegalArgumentException("Machine is empty");
        if (isBlank(cpu)) throw new IllegalArgumentException("CPU is empty");
        if (cpuCount < 1 || cpuCount > 64) throw new IllegalArgumentException("CPU count must be 1..64");
        if (ramMb < 128 || ramMb > 262144) throw new IllegalArgumentException("RAM must be 128..262144 MB");
        if (vncPort < 5900 || vncPort > 65535) throw new IllegalArgumentException("Invalid VNC port");
        if (extraArguments == null) extraArguments = new ArrayList<>();
        for (String arg : extraArguments) if (arg == null || arg.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Invalid extra QEMU argument");
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
