/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.sysui;

import static android.content.pm.ActivityInfo.CONFIG_ASSETS_PATHS;
import static android.content.pm.ActivityInfo.CONFIG_FONT_SCALE;
import static android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION;
import static android.content.pm.ActivityInfo.CONFIG_LOCALE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_INIT;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_SYSUI_EVENTS;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.SurfaceControlRegistry;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ExecutorUtils;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Handles event callbacks from SysUI that can be used within the Shell.
 */
public class ShellController implements RemoteCallable<ShellController> {
    private static final String TAG = ShellController.class.getSimpleName();

    private final Context mContext;
    private final ShellInit mShellInit;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellExecutor mMainExecutor;
    private final ShellInterfaceImpl mImpl = new ShellInterfaceImpl();
    private final ExternalInterfaceProviderImpl mExternalInterfaceProvider =
            new ExternalInterfaceProviderImpl(this);

    private final CopyOnWriteArrayList<ConfigurationChangeListener> mConfigChangeListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<KeyguardChangeListener> mKeyguardChangeListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<UserChangeListener> mUserChangeListeners =
            new CopyOnWriteArrayList<>();

    private ArrayMap<String, Supplier<ExternalInterfaceBinder>> mExternalInterfaceSuppliers =
            new ArrayMap<>();
    // References to the existing interfaces, to be invalidated when they are recreated, mapped
    // by package.
    private final Map<String, ArrayMap<String, ExternalInterfaceBinder>>
            mPackageToExternalInterfaces = new ArrayMap<>();

    private Configuration mLastConfiguration;


    public ShellController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellExecutor mainExecutor) {
        mContext = context;
        mShellInit = shellInit;
        mShellCommandHandler = shellCommandHandler;
        mMainExecutor = mainExecutor;
        shellInit.addInitCallback(this::onInit, this);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    private void onInit() {
        mShellCommandHandler.addDumpCallback(this::dump, this);
    }

    /**
     * Returns the external interface to this controller.
     */
    public ShellInterface asShell() {
        return mImpl;
    }

    /**
     * Adds a new configuration listener. The configuration change callbacks are not made in any
     * particular order.
     */
    public void addConfigurationChangeListener(ConfigurationChangeListener listener) {
        mConfigChangeListeners.remove(listener);
        mConfigChangeListeners.add(listener);
    }

    /**
     * Removes an existing configuration listener.
     */
    public void removeConfigurationChangeListener(ConfigurationChangeListener listener) {
        mConfigChangeListeners.remove(listener);
    }

    /**
     * Adds a new Keyguard listener. The Keyguard change callbacks are not made in any
     * particular order.
     */
    public void addKeyguardChangeListener(KeyguardChangeListener listener) {
        mKeyguardChangeListeners.remove(listener);
        mKeyguardChangeListeners.add(listener);
    }

    /**
     * Removes an existing Keyguard listener.
     */
    public void removeKeyguardChangeListener(KeyguardChangeListener listener) {
        mKeyguardChangeListeners.remove(listener);
    }

    /**
     * Adds a new user-change listener. The user change callbacks are not made in any
     * particular order.
     */
    public void addUserChangeListener(UserChangeListener listener) {
        mUserChangeListeners.remove(listener);
        mUserChangeListeners.add(listener);
    }

    /**
     * Removes an existing user-change listener.
     */
    public void removeUserChangeListener(UserChangeListener listener) {
        mUserChangeListeners.remove(listener);
    }

    /**
     * Adds an interface that can be called from a remote process. This method takes a supplier
     * because each binder reference is valid for a single process, and in multi-user mode, SysUI
     * will request new binder instances for each instance of Launcher that it provides binders
     * to.
     *
     * @param extra the key for the interface, {@see ShellSharedConstants}
     * @param binderSupplier the supplier of the binder to pass to the external process
     * @param callerInstance the instance of the caller, purely for logging
     */
    public void addExternalInterface(String extra, Supplier<ExternalInterfaceBinder> binderSupplier,
            Object callerInstance) {
        //ProtoLog.v(WM_SHELL_INIT, "Adding external interface from %s with key %s",
        //        callerInstance.getClass().getSimpleName(), extra);
        if (mExternalInterfaceSuppliers.containsKey(extra)) {
            throw new IllegalArgumentException("Supplier with same key already exists: "
                    + extra);
        }
        mExternalInterfaceSuppliers.put(extra, binderSupplier);
    }

    /**
     * Updates the given bundle with the set of external interfaces, invalidating the old set of
     * binders.
     */
    @VisibleForTesting
    public void createExternalInterfaces(Bundle output) {
        // Create new binders for each key
        for (int i = 0; i < mExternalInterfaceSuppliers.size(); i++) {
            final String key = mExternalInterfaceSuppliers.keyAt(i);
            final IBinder binder = createExternalInterface(key, mContext.getPackageName());
            if (binder != null) {
                output.putBinder(key, binder);
            }
        }
    }

    /**
     * Creates an external interface for the specified key. Each binder reference is valid for a
     * single process, so the interfaces are mapped by the calling package.
     * @param key the key for the interface, {@see ShellSharedConstants}
     * @param callingPackage the calling package name.
     * @return
     */
    @VisibleForTesting
    public IBinder createExternalInterface(String key, String callingPackage) {
        final Supplier<ExternalInterfaceBinder> supplier = mExternalInterfaceSuppliers.get(key);
        if (supplier != null) {
            final ExternalInterfaceBinder interfaceBinder = supplier.get();
            final ArrayMap<String, ExternalInterfaceBinder> externalInterfaces =
                    mPackageToExternalInterfaces.getOrDefault(callingPackage, new ArrayMap<>());
            final ExternalInterfaceBinder oldValue = externalInterfaces.put(key, interfaceBinder);
            if (oldValue != null) {
                oldValue.invalidate();
            }
            mPackageToExternalInterfaces.put(callingPackage, externalInterfaces);
            return interfaceBinder.asBinder();
        }
        return null;
    }

    @VisibleForTesting
    void onConfigurationChanged(Configuration newConfig) {
        // The initial config is send on startup and doesn't trigger listener callbacks
        if (mLastConfiguration == null) {
            mLastConfiguration = new Configuration(newConfig);
            ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "Initial Configuration: %s", newConfig);
            return;
        }

        final int diff = newConfig.diff(mLastConfiguration);
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "New configuration change: %s", newConfig);
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "\tchanges=%s",
                Configuration.configurationDiffToString(diff));
        final boolean densityFontScaleChanged = (diff & CONFIG_FONT_SCALE) != 0
                || (diff & ActivityInfo.CONFIG_DENSITY) != 0;
        final boolean smallestScreenWidthChanged = (diff & CONFIG_SMALLEST_SCREEN_SIZE) != 0;
        final boolean themeChanged = (diff & CONFIG_ASSETS_PATHS) != 0
                || (diff & CONFIG_UI_MODE) != 0;
        final boolean localOrLayoutDirectionChanged = (diff & CONFIG_LOCALE) != 0
                || (diff & CONFIG_LAYOUT_DIRECTION) != 0;

        // Update the last configuration and call listeners
        mLastConfiguration.updateFrom(newConfig);
        for (ConfigurationChangeListener listener : mConfigChangeListeners) {
            listener.onConfigurationChanged(newConfig);
            if (densityFontScaleChanged) {
                listener.onDensityOrFontScaleChanged();
            }
            if (smallestScreenWidthChanged) {
                listener.onSmallestScreenWidthChanged();
            }
            if (themeChanged) {
                listener.onThemeChanged();
            }
            if (localOrLayoutDirectionChanged) {
                listener.onLocaleOrLayoutDirectionChanged();
            }
        }
    }

    @VisibleForTesting
    void onKeyguardVisibilityChanged(boolean visible, boolean occluded, boolean animatingDismiss) {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "Keyguard visibility changed: visible=%b "
                + "occluded=%b animatingDismiss=%b", visible, occluded, animatingDismiss);
        for (KeyguardChangeListener listener : mKeyguardChangeListeners) {
            listener.onKeyguardVisibilityChanged(visible, occluded, animatingDismiss);
        }
    }

    @VisibleForTesting
    void onKeyguardDismissAnimationFinished() {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "Keyguard dismiss animation finished");
        for (KeyguardChangeListener listener : mKeyguardChangeListeners) {
            listener.onKeyguardDismissAnimationFinished();
        }
    }

    @VisibleForTesting
    void onUserChanged(int newUserId, @NonNull Context userContext) {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "User changed: id=%d", newUserId);
        for (UserChangeListener listener : mUserChangeListeners) {
            listener.onUserChanged(newUserId, userContext);
        }
    }

    @VisibleForTesting
    void onUserProfilesChanged(@NonNull List<UserInfo> profiles) {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "User profiles changed");
        for (UserChangeListener listener : mUserChangeListeners) {
            listener.onUserProfilesChanged(profiles);
        }
    }

    private void handleInit() {
        SurfaceControlRegistry.createProcessInstance(mContext);
        mShellInit.init();
    }

    private void handleDump(PrintWriter pw) {
        mShellCommandHandler.dump(pw);
        SurfaceControlRegistry.dump(100 /* limit */, false /* runGc */, pw);
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mConfigChangeListeners=" + mConfigChangeListeners.size());
        pw.println(innerPrefix + "mLastConfiguration=" + mLastConfiguration);
        pw.println(innerPrefix + "mKeyguardChangeListeners=" + mKeyguardChangeListeners.size());
        pw.println(innerPrefix + "mUserChangeListeners=" + mUserChangeListeners.size());

        if (!mPackageToExternalInterfaces.isEmpty()) {
            pw.println(innerPrefix + "mExternalInterfaces={");
            for (String key : mPackageToExternalInterfaces.keySet()) {
                pw.println(innerPrefix + "\t" + key + ": " + mPackageToExternalInterfaces.get(key));
            }
            pw.println(innerPrefix + "}");
        }
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class ShellInterfaceImpl implements ShellInterface {
        @Override
        public void onInit() {
            try {
                mMainExecutor.executeBlocking(() -> ShellController.this.handleInit());
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to initialize the Shell in 2s", e);
            }
        }

        @Override
        public void onConfigurationChanged(Configuration newConfiguration) {
            mMainExecutor.execute(() ->
                    ShellController.this.onConfigurationChanged(newConfiguration));
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
                boolean animatingDismiss) {
            mMainExecutor.execute(() ->
                    ShellController.this.onKeyguardVisibilityChanged(visible, occluded,
                            animatingDismiss));
        }

        @Override
        public void onKeyguardDismissAnimationFinished() {
            mMainExecutor.execute(() ->
                    ShellController.this.onKeyguardDismissAnimationFinished());
        }

        @Override
        public void onUserChanged(int newUserId, @NonNull Context userContext) {
            mMainExecutor.execute(() ->
                    ShellController.this.onUserChanged(newUserId, userContext));
        }

        @Override
        public void onUserProfilesChanged(@NonNull List<UserInfo> profiles) {
            mMainExecutor.execute(() ->
                    ShellController.this.onUserProfilesChanged(profiles));
        }

        @Override
        public boolean handleCommand(String[] args, PrintWriter pw) {
            try {
                boolean[] result = new boolean[1];
                mMainExecutor.executeBlocking(() -> {
                    result[0] = mShellCommandHandler.handleCommand(args, pw);
                });
                return result[0];
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to handle Shell command in 2s", e);
            }
        }

        @Override
        public void createExternalInterfaces(Bundle bundle) {
            try {
                mMainExecutor.executeBlocking(() -> {
                    ShellController.this.createExternalInterfaces(bundle);
                });
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to get Shell command in 2s", e);
            }
        }

        @Override
        public ExternalInterfaceProvider getExternalInterfaceProvider() {
            return mMainExecutor.executeBlockingForResult(() -> mExternalInterfaceProvider,
                    ExternalInterfaceProvider.class);
        }

        @Override
        public void dump(PrintWriter pw) {
            try {
                mMainExecutor.executeBlocking(() -> ShellController.this.handleDump(pw));
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to dump the Shell in 2s", e);
            }
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class ExternalInterfaceProviderImpl implements ExternalInterfaceProvider {

        private final ShellController mController;

        private ExternalInterfaceProviderImpl(ShellController controller) {
            mController = controller;
        }

        @Override
        public IBinder createExternalInterface(String key) {
            final int uid = Binder.getCallingUid();
            final IBinder[] out = {null};
            ExecutorUtils.executeRemoteCallWithTaskPermission(mController,
                    "createExternalInterfaceForExtension",
                    (controller) -> out[0] = mController.createExternalInterface(key,
                            mController.getContext().getPackageManager().getNameForUid(uid)),
                    true /* blocking */);
            return out[0];
        }
    }
}
