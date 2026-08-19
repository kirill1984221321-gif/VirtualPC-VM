package com.virtualpcvm;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runtime diagnostics for the extracted embedded QEMU. */
public final class QemuSelfTest {
    private QemuSelfTest() {}

    public static String run(QemuRuntime runtime) {
        StringBuilder report = new StringBuilder();
        try {
            runtime.prepare();
            report.append("runtime=PASS\n");
            report.append("binary=").append(runtime.getBinary().getAbsolutePath()).append('\n');
            report.append("libraries=").append(runtime.getLibraryCount()).append('\n');
        } catch (Throwable error) {
            return "runtime=FAIL\n" + error;
        }

        report.append(runVersion(runtime));
        report.append(runAliveTest(runtime, "none", buildNoneArgs(runtime)));
        report.append(runAliveTest(runtime, "pc", buildPcArgs(runtime)));
        report.append(runVncTest(runtime));
        return report.toString();
    }

    private static String runVersion(QemuRuntime runtime) {
        QemuProcess process = new QemuProcess(runtime);
        try {
            process.start(Arrays.asList(runtime.getBinary().getAbsolutePath(), "--version"), null);
            long deadline = System.currentTimeMillis() + 10_000L;
            while (process.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(50);
            Thread.sleep(100);
            String out = process.getStdout();
            boolean ok = process.getExitCode() == 0 && out.contains("QEMU emulator version 11.0.3");
            return "version=" + (ok ? "PASS" : "FAIL") + " exit=" + process.getExitCode() + " output=" + out.trim() + "\n";
        } catch (Throwable error) {
            return "version=FAIL " + error + "\n";
        } finally {
            if (process.isRunning()) process.stop();
        }
    }

    private static String runAliveTest(QemuRuntime runtime, String name, List<String> args) {
        QemuProcess process = new QemuProcess(runtime);
        try {
            process.start(args, null);
            Thread.sleep(1200);
            boolean alive = process.isRunning();
            process.stop();
            return name + "=" + (alive ? "PASS" : "FAIL") + " exit=" + process.getExitCode() + "\n";
        } catch (Throwable error) {
            process.stop();
            return name + "=FAIL " + error + "\n";
        }
    }

    private static String runVncTest(QemuRuntime runtime) {
        QemuProcess process = new QemuProcess(runtime);
        try {
            VMConfig config = new VMConfig();
            config.machine = "pc";
            config.cpu = "qemu64";
            config.ramMb = 512;
            config.display = "vnc";
            config.vncPort = 5901;
            config.network = "off";
            config.video = "std";
            config.extraArguments = new ArrayList<>(Arrays.asList("-S", "-monitor", "none"));
            List<String> command = new QemuCommandBuilder().build(config, runtime);
            process.start(command, null);
            boolean endpoint = waitForVnc("127.0.0.1", 5901, 5000);
            String banner = endpoint ? readVncBanner() : "";
            process.stop();
            return "vnc=" + (endpoint && banner.startsWith("RFB ") ? "PASS" : "FAIL") +
                    " endpoint=" + endpoint + " banner=" + banner.trim() + "\n";
        } catch (Throwable error) {
            process.stop();
            return "vnc=FAIL " + error + "\n";
        }
    }

    private static List<String> buildNoneArgs(QemuRuntime runtime) {
        return Arrays.asList(runtime.getBinary().getAbsolutePath(), "-M", "none", "-display", "none", "-nodefaults", "-S", "-monitor", "none");
    }

    private static List<String> buildPcArgs(QemuRuntime runtime) {
        return Arrays.asList(runtime.getBinary().getAbsolutePath(), "-L", runtime.getFirmwareDir().getAbsolutePath(),
                "-M", "pc", "-cpu", "qemu64", "-m", "512M", "-display", "none", "-nodefaults", "-S", "-monitor", "none");
    }

    private static boolean waitForVnc(String host, int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
                return true;
            } catch (Exception ignored) {
                Thread.sleep(100);
            }
        }
        return false;
    }

    private static String readVncBanner() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5901), 700);
            socket.setSoTimeout(700);
            byte[] buffer = new byte[12];
            int offset = 0;
            while (offset < buffer.length) {
                int read = socket.getInputStream().read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.US_ASCII);
        } catch (Exception ignored) {
            return "";
        }
    }
}
