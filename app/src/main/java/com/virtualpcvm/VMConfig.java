package com.virtualpcvm;

import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Independent VM configuration schema. */
public final class VMConfig {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final List<String> SUPPORTED_ARCHITECTURES = Collections.singletonList("x86_64");

    @SerializedName("schemaVersion") public int schemaVersion = CURRENT_SCHEMA_VERSION;
    @SerializedName("id") public String id = UUID.randomUUID().toString();
    @SerializedName("name") public String name = "Новая виртуальная машина";
    @SerializedName("osType") public String osType = "Other";
    @SerializedName("architecture") public String architecture = "x86_64";
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
        c.architecture = architecture; c.machine = machine; c.cpu = cpu; c.cpuCount = cpuCount; c.ramMb = ramMb;
        c.hdd = hdd; c.iso = iso; c.cdrom = cdrom; c.bootOrder = bootOrder;
        c.display = display; c.vncPort = vncPort; c.video = video; c.network = network;
        c.extraArguments = new ArrayList<>(extraArguments == null ? Collections.<String>emptyList() : extraArguments);
        return c;
    }

    public void validate() {
        if (isBlank(id)) throw new IllegalArgumentException("VM id is empty");
        if (isBlank(name)) throw new IllegalArgumentException("VM name is empty");
        if (!VMStorage.isValidId(id)) throw new IllegalArgumentException("VM id is invalid");
        if (!SUPPORTED_ARCHITECTURES.contains(architecture)) throw new IllegalArgumentException("Unsupported architecture: " + architecture);
        if (isBlank(machine)) throw new IllegalArgumentException("Machine is empty");
        if (isBlank(cpu)) throw new IllegalArgumentException("CPU is empty");
        if (cpuCount < 1 || cpuCount > 64) throw new IllegalArgumentException("CPU count must be 1..64");
        if (ramMb < 128 || ramMb > 262144) throw new IllegalArgumentException("RAM must be 128..262144 MB");
        if (vncPort < 5900 || vncPort > 65535) throw new IllegalArgumentException("Invalid VNC port");
        if (extraArguments == null) extraArguments = new ArrayList<>();
        for (String arg : extraArguments) {
            if (arg == null || arg.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Invalid extra QEMU argument");
        }
    }

    public void validateForStart() {
        validate();
        requireFile(hdd, "HDD");
        requireFile(iso, "ISO");
        requireFile(cdrom, "CD/DVD");
    }

    public static List<String> supportedArchitectures() { return SUPPORTED_ARCHITECTURES; }

    private static void requireFile(String path, String label) {
        if (isBlank(path)) return;
        if (path.startsWith("content://") || path.startsWith("file://")) {
            throw new IllegalArgumentException(label + " must be an accessible filesystem path, not a URI");
        }
        File file = new File(path);
        if (!file.isAbsolute() || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException(label + " path is invalid or unreadable: " + path);
        }
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
