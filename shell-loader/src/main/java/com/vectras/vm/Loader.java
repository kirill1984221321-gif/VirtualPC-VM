package com.vectras.vm;

public class Loader {
    public static void main(String[] args) {
        try {
            android.content.pm.PackageInfo targetInfo = (android.os.Build.VERSION.SDK_INT <= 32) ?
                    android.app.ActivityThread.getPackageManager().getPackageInfo(
                            BuildConfig.APPLICATION_ID,
                            android.content.pm.PackageManager.GET_SIGNATURES,
                            0) :
                    android.app.ActivityThread.getPackageManager().getPackageInfo(
                            BuildConfig.APPLICATION_ID,
                            (long) android.content.pm.PackageManager.GET_SIGNATURES,
                            0);

            if (targetInfo == null) {
                throw new IllegalStateException(
                        BuildConfig.packageNotInstalledErrorText.replace("ARCH", preferredAbi()));
            }

            if (targetInfo.signatures == null || targetInfo.signatures.length != 1
                    || BuildConfig.SIGNATURE != targetInfo.signatures[0].hashCode()) {
                throw new IllegalStateException(BuildConfig.packageSignatureMismatchErrorText);
            }

            android.util.Log.i(
                    BuildConfig.logTag,
                    "loading " + targetInfo.applicationInfo.sourceDir
                            + "::" + BuildConfig.CLASS_ID
                            + "::main of " + BuildConfig.APPLICATION_ID
                            + " application (commit " + BuildConfig.COMMIT + ")");

            Class<?> targetClass = Class.forName(
                    BuildConfig.CLASS_ID,
                    true,
                    new dalvik.system.PathClassLoader(
                            targetInfo.applicationInfo.sourceDir,
                            null,
                            ClassLoader.getSystemClassLoader()));
            targetClass.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                cause.printStackTrace(System.err);
            } else {
                e.printStackTrace(System.err);
            }
        } catch (Throwable e) {
            android.util.Log.e(BuildConfig.logTag, "Loader error", e);
            e.printStackTrace(System.err);
        }
    }

    private static String preferredAbi() {
        String[] supportedAbis = android.os.Build.SUPPORTED_ABIS;
        if (supportedAbis != null && supportedAbis.length > 0 && supportedAbis[0] != null) {
            return supportedAbis[0];
        }
        return "unknown";
    }
}
