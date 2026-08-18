package com.vectras.vm;

public class Loader {
    public static void main(String[] args) {
        try {
            android.content.pm.PackageInfo targetInfo = (android.os.Build.VERSION.SDK_INT <= 32) ?
                    android.app.ActivityThread.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, android.content.pm.PackageManager.GET_SIGNATURES, 0) :
                    android.app.ActivityThread.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, (long) android.content.pm.PackageManager.GET_SIGNATURES, 0);
            assert targetInfo != null : BuildConfig.packageNotInstalledErrorText.replace("ARCH", android.os.Build.SUPPORTED_ABIS[0]);
            assert targetInfo.signatures.length == 1 && BuildConfig.SIGNATURE == targetInfo.signatures[0].hashCode() : BuildConfig.packageSignatureMismatchErrorText;
            android.util.Log.i(BuildConfig.logTag, "loading " + targetInfo.applicationInfo.sourceDir + "::" + BuildConfig.CLASS_ID + "::main of " + BuildConfig.APPLICATION_ID + " application (commit " + BuildConfig.COMMIT + ")");
            Class<?> targetClass = Class.forName(BuildConfig.CLASS_ID, true,
                    new dalvik.system.PathClassLoader(targetInfo.applicationInfo.sourceDir, null, ClassLoader.getSystemClassLoader()));
            targetClass.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (AssertionError e) {
            System.err.println(e.getMessage());
        } catch (java.lang.reflect.InvocationTargetException e) {
            e.getCause().printStackTrace(System.err);
        } catch (Throwable e) {
            android.util.Log.e(BuildConfig.logTag, "Loader error", e);
            e.printStackTrace(System.err);
        }
    }
}
