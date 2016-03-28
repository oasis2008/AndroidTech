package com.ycl.androidtech.utils.dex;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;

/**
 * Created by yuchengluo on 2015/10/30.
 */
public class MultiDex {


    static final String TAG = "MultiDex";

    private static final String SECONDARY_FOLDER_NAME = "secondary-dexes";

    private static final int MAX_SUPPORTED_SDK_VERSION = 20;

    private static final int MIN_SDK_VERSION = 4;

    private static final int VM_WITH_MULTIDEX_VERSION_MAJOR = 2;

    private static final int VM_WITH_MULTIDEX_VERSION_MINOR = 1;

    private static final Set<String> installedApk = new HashSet<String>();

    private static final boolean IS_VM_MULTIDEX_CAPABLE = isVMMultidexCapable(System.getProperty("java.vm.version"));

    private MultiDex() {
    }

    /**
     * Patches the application context class loader by appending extra dex files
     * loaded from the application apk. This method should be called in the
     * attachBaseContext of your {@link Application}, see
     * {@link MultiDexApplication} for more explanation and an example.
     *
     * @param context application context.
     * @throws RuntimeException if an error occurred preventing the classloader extension.
     */
    public static void install(Context context) {
        Log.i(TAG, "install");
        if (IS_VM_MULTIDEX_CAPABLE) {
            Log.i(TAG, "VM has multidex support, MultiDex support library is disabled.");
            try {
                clearOldDexDir(context);
            } catch (Throwable t) {
                Log.w(TAG, "Something went wrong when trying to clear old MultiDex extraction, "
                        + "continuing without cleaning.", t);
            }
            return;
        }

        if (Build.VERSION.SDK_INT < MIN_SDK_VERSION) {
            throw new RuntimeException("Multi dex installation failed. SDK " + Build.VERSION.SDK_INT
                    + " is unsupported. Min SDK version is " + MIN_SDK_VERSION + ".");
        }

        try {
            ApplicationInfo applicationInfo = getApplicationInfo(context);
            if (applicationInfo == null) {
                // Looks like running on a test Context, so just return without
                // patching.
                return;
            }

            synchronized (installedApk) {
                String apkPath = applicationInfo.sourceDir;
                if (installedApk.contains(apkPath)) {
                    return;
                }
                installedApk.add(apkPath);

                if (Build.VERSION.SDK_INT > MAX_SUPPORTED_SDK_VERSION) {
                    Log.w(TAG, "MultiDex is not guaranteed to work in SDK version " + Build.VERSION.SDK_INT
                            + ": SDK version higher than " + MAX_SUPPORTED_SDK_VERSION + " should be backed by "
                            + "runtime with built-in multidex capabilty but it's not the "
                            + "case here: java.vm.version=\"" + System.getProperty("java.vm.version") + "\"");
                }

				/*
				 * The patched class loader is expected to be a descendant of
				 * dalvik.system.BaseDexClassLoader. We modify its
				 * dalvik.system.DexPathList pathList field to append additional
				 * DEX file entries.
				 */
                ClassLoader loader;
                try {
                    loader = context.getClassLoader();
                } catch (RuntimeException e) {
					/*
					 * Ignore those exceptions so that we don't break tests
					 * relying on Context like a android.test.mock.MockContext
					 * or a android.content.ContextWrapper with a null base
					 * Context.
					 */
                    Log.w(TAG, "Failure while trying to obtain Context class loader. "
                            + "Must be running in test mode. Skip patching.", e);
                    return;
                }
                if (loader == null) {
                    // Note, the context class loader is null when running
                    // Robolectric tests.
                    Log.e(TAG, "Context class loader is null. Must be running in test mode. " + "Skip patching.");
                    return;
                }

                File dexDir = new File(context.getFilesDir(), SECONDARY_FOLDER_NAME);
                List<File> files = MultiDexExtractor.load(context, applicationInfo, dexDir, false);
                if (checkValidZipFiles(files)) {
                    installSecondaryDexes(loader, dexDir, files);
                } else {
                    Log.w(TAG, "Files were not valid zip files.  Forcing a reload.");
                    // Try again, but this time force a reload of the zip file.
                    files = MultiDexExtractor.load(context, applicationInfo, dexDir, true);

                    if (checkValidZipFiles(files)) {
                        installSecondaryDexes(loader, dexDir, files);
                    } else {
                        // Second time didn't work, give up
                        throw new RuntimeException("Zip files were not valid.");
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Multidex installation failure", e);
            throw new RuntimeException("Multi dex installation failed (" + e.getMessage() + ").");
        }
        Log.i(TAG, "install done");
    }

    private static ApplicationInfo getApplicationInfo(Context context) throws PackageManager.NameNotFoundException {
        PackageManager pm;
        String packageName;
        try {
            pm = context.getPackageManager();
            packageName = context.getPackageName();
        } catch (RuntimeException e) {
			/*
			 * Ignore those exceptions so that we don't break tests relying on
			 * Context like a android.test.mock.MockContext or a
			 * android.content.ContextWrapper with a null base Context.
			 */
            Log.w(TAG, "Failure while trying to obtain ApplicationInfo from Context. "
                    + "Must be running in test mode. Skip patching.", e);
            return null;
        }
        if (pm == null || packageName == null) {
            // This is most likely a mock context, so just return without
            // patching.
            return null;
        }
        ApplicationInfo applicationInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        return applicationInfo;
    }

    /**
     * Identifies if the current VM has a native support for multidex, meaning
     * there is no need for additional installation by this library.
     *
     * @return true if the VM handles multidex
     */
	/* package visible for test */
    static boolean isVMMultidexCapable(String versionString) {
        boolean isMultidexCapable = false;
        if (versionString != null) {
            Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)(\\.\\d+)?").matcher(versionString);
            if (matcher.matches()) {
                try {
                    int major = Integer.parseInt(matcher.group(1));
                    int minor = Integer.parseInt(matcher.group(2));
                    isMultidexCapable = (major > VM_WITH_MULTIDEX_VERSION_MAJOR)
                            || ((major == VM_WITH_MULTIDEX_VERSION_MAJOR) && (minor >= VM_WITH_MULTIDEX_VERSION_MINOR));
                } catch (NumberFormatException e) {
                    // let isMultidexCapable be false
                }
            }
        }
        Log.i(TAG, "VM with version " + versionString
                + (isMultidexCapable ? " has multidex support" : " does not have multidex support"));
        return isMultidexCapable;
    }

    public static void installSecondaryDexes(ClassLoader loader, File dexDir, List<File> files)
            throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, InvocationTargetException,
            NoSuchMethodException, IOException {
        if (!files.isEmpty()) {
            if(isAliyunOs()){
                AliYunos.install(loader, files,dexDir.getAbsolutePath());
            }else if (Build.VERSION.SDK_INT >= 19) {
                V19.install(loader, files, dexDir);
            } else if (Build.VERSION.SDK_INT >= 14) {
                V14.install(loader, files, dexDir);
            } else {
                V4.install(loader, files);
            }
        }
    }

    private static boolean isAliyunOs() {
        try {
            Class.forName(CLASS_LOADER_ALIYUN);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns whether all files in the list are valid zip files. If
     * {@code files} is empty, then returns true.
     */
    private static boolean checkValidZipFiles(List<File> files) {
        for (File file : files) {
            if (!MultiDexExtractor.verifyZipFile(file)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Locates a given field anywhere in the class inheritance hierarchy.
     *
     * @param instance an object to search the field into.
     * @param name     field name
     * @return a field object
     * @throws NoSuchFieldException if the field cannot be located
     */
    private static Field findField(Object instance, String name) throws NoSuchFieldException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);

                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }

                return field;
            } catch (NoSuchFieldException e) {
                // ignore and search next
            }
        }

        throw new NoSuchFieldException("Field " + name + " not found in " + instance.getClass());
    }

    /**
     * Locates a given method anywhere in the class inheritance hierarchy.
     *
     * @param instance       an object to search the method into.
     * @param name           method name
     * @param parameterTypes method parameter types
     * @return a method object
     * @throws NoSuchMethodException if the method cannot be located
     */
    private static Method findMethod(Object instance, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Method method = clazz.getDeclaredMethod(name, parameterTypes);

                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }

                return method;
            } catch (NoSuchMethodException e) {
                // ignore and search next
            }
        }

        throw new NoSuchMethodException("Method " + name + " with parameters " + Arrays.asList(parameterTypes)
                + " not found in " + instance.getClass());
    }

    /**
     * Replace the value of a field containing a non null array, by a new array
     * containing the elements of the original array plus the elements of
     * extraElements.
     *
     * @param instance      the instance whose field is to be modified.
     * @param fieldName     the field to modify.
     * @param extraElements elements to append at the end of the array.
     */
    private static void expandFieldArray(Object instance, String fieldName, Object[] extraElements)
            throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        Field jlrField = findField(instance, fieldName);
        Object[] original = (Object[]) jlrField.get(instance);
        Object[] combined = (Object[]) Array.newInstance(original.getClass().getComponentType(), original.length
                + extraElements.length);
        System.arraycopy(extraElements, 0, combined, 0, extraElements.length);
        System.arraycopy(original, 0, combined, extraElements.length, original.length);
        jlrField.set(instance, combined);
    }

    private static void clearOldDexDir(Context context) throws Exception {
        ApplicationInfo applicationInfo = getApplicationInfo(context);
        if (applicationInfo == null) {
            // Looks like running on a test Context, so just return.
            return;
        }

        synchronized (installedApk) {
            String apkPath = applicationInfo.sourceDir;
            if (installedApk.contains(apkPath)) {
                return;
            }
            installedApk.add(apkPath);
        }
        File dexDir = new File(context.getFilesDir(), SECONDARY_FOLDER_NAME);
        if (dexDir.isDirectory()) {
            Log.i(TAG, "Clearing old secondary dex dir (" + dexDir.getPath() + ").");
            File[] files = dexDir.listFiles();
            if (files == null) {
                Log.w(TAG, "Failed to list secondary dex dir content (" + dexDir.getPath() + ").");
                return;
            }
            for (File oldFile : files) {
                Log.i(TAG, "Trying to delete old file " + oldFile.getPath() + " of size " + oldFile.length());
                if (!oldFile.delete()) {
                    Log.w(TAG, "Failed to delete old file " + oldFile.getPath());
                } else {
                    Log.i(TAG, "Deleted old file " + oldFile.getPath());
                }
            }
            if (!dexDir.delete()) {
                Log.w(TAG, "Failed to delete secondary dex dir " + dexDir.getPath());
            } else {
                Log.i(TAG, "Deleted old secondary dex dir " + dexDir.getPath());
            }
        }
    }

    /**
     * Installer for platform versions 19.
     */
    private static final class V19 {

        private static void install(ClassLoader loader, List<File> additionalClassPathEntries, File optimizedDirectory)
                throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException,
                InvocationTargetException, NoSuchMethodException {
			/*
			 * The patched class loader is expected to be a descendant of
			 * dalvik.system.BaseDexClassLoader. We modify its
			 * dalvik.system.DexPathList pathList field to append additional DEX
			 * file entries.
			 */
            Field pathListField = findField(loader, "pathList");
            Object dexPathList = pathListField.get(loader);
            ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
            expandFieldArray(
                    dexPathList,
                    "dexElements",
                    makeDexElements(dexPathList, new ArrayList<File>(additionalClassPathEntries), optimizedDirectory,
                            suppressedExceptions));
            if (suppressedExceptions.size() > 0) {
                for (IOException e : suppressedExceptions) {
                    Log.w(TAG, "Exception in makeDexElement", e);
                }
                Field suppressedExceptionsField = findField(loader, "dexElementsSuppressedExceptions");
                IOException[] dexElementsSuppressedExceptions = (IOException[]) suppressedExceptionsField.get(loader);

                if (dexElementsSuppressedExceptions == null) {
                    dexElementsSuppressedExceptions = suppressedExceptions.toArray(new IOException[suppressedExceptions
                            .size()]);
                } else {
                    IOException[] combined = new IOException[suppressedExceptions.size()
                            + dexElementsSuppressedExceptions.length];
                    suppressedExceptions.toArray(combined);
                    System.arraycopy(dexElementsSuppressedExceptions, 0, combined, suppressedExceptions.size(),
                            dexElementsSuppressedExceptions.length);
                    dexElementsSuppressedExceptions = combined;
                }

                suppressedExceptionsField.set(loader, dexElementsSuppressedExceptions);
            }
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makeDexElements}
         * .
         */
        private static Object[] makeDexElements(Object dexPathList, ArrayList<File> files, File optimizedDirectory,
                                                ArrayList<IOException> suppressedExceptions) throws IllegalAccessException, InvocationTargetException,
                NoSuchMethodException {
            Method makeDexElements = findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class,
                    ArrayList.class);

            return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory, suppressedExceptions);
        }
    }

    /**
     * Installer for platform versions 14, 15, 16, 17 and 18.
     */
    private static final class V14 {

        private static void install(ClassLoader loader, List<File> additionalClassPathEntries, File optimizedDirectory)
                throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException,
                InvocationTargetException, NoSuchMethodException {
			/*
			 * The patched class loader is expected to be a descendant of
			 * dalvik.system.BaseDexClassLoader. We modify its
			 * dalvik.system.DexPathList pathList field to append additional DEX
			 * file entries.
			 */
            Field pathListField = findField(loader, "pathList");
            Object dexPathList = pathListField.get(loader);
            expandFieldArray(dexPathList, "dexElements",
                    makeDexElements(dexPathList, new ArrayList<File>(additionalClassPathEntries), optimizedDirectory));
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makeDexElements}
         * .
         */
        private static Object[] makeDexElements(Object dexPathList, ArrayList<File> files, File optimizedDirectory)
                throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
            Method makeDexElements = findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class);

            return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory);
        }
    }

    /**
     * Installer for platform versions 4 to 13.
     */
    private static final class V4 {
        private static void install(ClassLoader loader, List<File> additionalClassPathEntries)
                throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, IOException {
			/*
			 * The patched class loader is expected to be a descendant of
			 * dalvik.system.DexClassLoader. We modify its fields mPaths,
			 * mFiles, mZips and mDexs to append additional DEX file entries.
			 */
            int extraSize = additionalClassPathEntries.size();

            Field pathField = findField(loader, "path");

            StringBuilder path = new StringBuilder((String) pathField.get(loader));
            String[] extraPaths = new String[extraSize];
            File[] extraFiles = new File[extraSize];
            ZipFile[] extraZips = new ZipFile[extraSize];
            DexFile[] extraDexs = new DexFile[extraSize];
            for (ListIterator<File> iterator = additionalClassPathEntries.listIterator(); iterator.hasNext(); ) {
                File additionalEntry = iterator.next();
                String entryPath = additionalEntry.getAbsolutePath();
                path.append(':').append(entryPath);
                int index = iterator.previousIndex();
                extraPaths[index] = entryPath;
                extraFiles[index] = additionalEntry;
                extraZips[index] = new ZipFile(additionalEntry);
                extraDexs[index] = DexFile.loadDex(entryPath, entryPath + ".dex", 0);
            }

            pathField.set(loader, path.toString());
            expandFieldArray(loader, "mPaths", extraPaths);
            expandFieldArray(loader, "mFiles", extraFiles);
            expandFieldArray(loader, "mZips", extraZips);
            expandFieldArray(loader, "mDexs", extraDexs);
        }
    }

    private final static String CLASS_LOADER_ALIYUN = "dalvik.system.LexClassLoader";

    private static final class AliYunos {
        private static void install(ClassLoader loader, List<File> additionalClassPathEntries,String odexPath)
                throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, IOException {
            if (additionalClassPathEntries != null) {
                for (File file : additionalClassPathEntries) {
                    try {
                        String libPath = file.getAbsolutePath();
                        PathClassLoader localClassLoader = (PathClassLoader) loader;
                        //先写死，旧版本是context.getDir("dex", 0).getAbsolutePath()
                        new DexClassLoader(libPath, odexPath, libPath, localClassLoader);
                        String lexFileName = new File(libPath).getName();
                        lexFileName = lexFileName.replaceAll("\\.[a-zA-Z0-9]+", ".lex");

                        Class<?> classLexClassLoader = Class.forName(CLASS_LOADER_ALIYUN);
                        Constructor<?> constructorLexClassLoader = classLexClassLoader.getConstructor(String.class, String.class,
                                String.class, ClassLoader.class);
                        Object localLexClassLoader = constructorLexClassLoader.newInstance(odexPath
                                + File.separator + lexFileName, odexPath, libPath, localClassLoader);
                        ReflectUtil.setObjectField(
                                localClassLoader,
                                "mPaths",
                                appendArray(ReflectUtil.getObjectFieldValue(localClassLoader, "mPaths"),
                                        ReflectUtil.getObjectFieldValue(localLexClassLoader, "mRawDexPath"), true));
                        ReflectUtil.setObjectField(
                                localClassLoader,
                                "mFiles",
                                combineArray(ReflectUtil.getObjectFieldValue(localClassLoader, "mFiles"),
                                        ReflectUtil.getObjectFieldValue(localLexClassLoader, "mFiles"), true));
                        ReflectUtil.setObjectField(
                                localClassLoader,
                                "mZips",
                                combineArray(ReflectUtil.getObjectFieldValue(localClassLoader, "mZips"),
                                        ReflectUtil.getObjectFieldValue(localLexClassLoader, "mZips"), true));
                        ReflectUtil.setObjectField(
                                localClassLoader,
                                "mLexs",
                                combineArray(ReflectUtil.getObjectFieldValue(localClassLoader, "mLexs"),
                                        ReflectUtil.getObjectFieldValue(localLexClassLoader, "mDexs"), true));
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }

                }
            }

        }
    }

    private static Object appendArray(Object array, Object value, boolean insertAtFirst) {
        Class<?> localClass = array.getClass().getComponentType();
        int i = Array.getLength(array);
        int j = i + 1;
        Object localObject = Array.newInstance(localClass, j);
        if (insertAtFirst) {
            Array.set(localObject, 0, value);
            for (int k = 1; k < j; ++k) {
                Array.set(localObject, k, Array.get(array, k - 1));
            }
        } else {
            for (int k = 0; k < j; ++k) {
                if (k < i) {
                    Array.set(localObject, k, Array.get(array, k));
                } else {
                    Array.set(localObject, k, value);
                }
            }
        }
        return localObject;
    }

    private static Object combineArray(Object arrayLhs, Object arrayRhs, boolean rhsAtFirst) {
        if (rhsAtFirst) {
            Object temp = arrayLhs;
            arrayLhs = arrayRhs;
            arrayRhs = temp;
        }

        Class<?> localClass = arrayLhs.getClass().getComponentType();
        int i = Array.getLength(arrayLhs);
        int j = i + Array.getLength(arrayRhs);
        Object result = Array.newInstance(localClass, j);
        for (int k = 0; k < j; ++k) {
            if (k < i) {
                Array.set(result, k, Array.get(arrayLhs, k));
            } else {
                Array.set(result, k, Array.get(arrayRhs, k - i));
            }
        }
        return result;
    }
}