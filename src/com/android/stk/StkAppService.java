/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.stk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.cat.AppInterface;
import com.android.internal.telephony.cat.Menu;
import com.android.internal.telephony.cat.Item;
import com.android.internal.telephony.cat.Input;
import com.android.internal.telephony.cat.ResultCode;
import com.android.internal.telephony.cat.CatCmdMessage;
import com.android.internal.telephony.cat.CatCmdMessage.BrowserSettings;
import com.android.internal.telephony.cat.CatEventMessage;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.CatResponseMessage;
import com.android.internal.telephony.cat.ComprehensionTlvTag;
import com.android.internal.telephony.cat.EventCode;
import com.android.internal.telephony.cat.LaunchBrowserMode;
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * SIM toolkit application level service. Interacts with Telephopny messages,
 * application's launch and user input from STK UI elements.
 *
 */
public class StkAppService extends Service implements Runnable {

    // members
    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;
    private AppInterface mStkService;
    private Context mContext = null;
    private CatCmdMessage mMainCmd = null;
    private CatCmdMessage mCurrentCmd = null;
    private Menu mCurrentMenu = null;
    private String lastSelectedItem = null;
    private boolean mMenuIsVisibile = false;
    private boolean responseNeeded = true;
    private boolean mCmdInProgress = false;
    private NotificationManager mNotificationManager = null;
    private LinkedList<DelayedCmd> mCmdsQ = null;
    private boolean mLaunchBrowser = false;
    private BrowserSettings mBrowserSettings = null;
    static StkAppService sInstance = null;

    private IActivityManager mActivityManager;

    // Used for setting FLAG_ACTIVITY_NO_USER_ACTION when
    // creating an intent.
    private enum InitiatedByUserAction {
        yes,            // The action was started via a user initiated action
        unknown,        // Not known for sure if user initated the action
    }

    // constants
    static final String OPCODE = "op";
    static final String CMD_MSG = "cmd message";
    static final String RES_ID = "response id";
    static final String MENU_SELECTION = "menu selection";
    static final String INPUT = "input";
    static final String HELP = "help";
    static final String CONFIRMATION = "confirm";
    static final String CHOICE = "choice";

    // operations ids for different service functionality.
    static final int OP_CMD = 1;
    static final int OP_RESPONSE = 2;
    static final int OP_LAUNCH_APP = 3;
    static final int OP_END_SESSION = 4;
    static final int OP_BOOT_COMPLETED = 5;
    private static final int OP_DELAYED_MSG = 6;
    static final int OP_USER_ACTIVITY = 7;

    // Response ids
    static final int RES_ID_MENU_SELECTION = 11;
    static final int RES_ID_INPUT = 12;
    static final int RES_ID_CONFIRM = 13;
    static final int RES_ID_DONE = 14;
    static final int RES_ID_CHOICE = 15;

    static final int RES_ID_TIMEOUT = 20;
    static final int RES_ID_BACKWARD = 21;
    static final int RES_ID_END_SESSION = 22;
    static final int RES_ID_EXIT = 23;

    // Additional information (see 3GPP TS 11.14 for details)
    static final int ADDITIONAL_INFO_SCREEN_BUSY = 1;
    static final int STK_BROWSER_UNAVAILABLE = 0x02;

    static final int YES = 1;
    static final int NO = 0;

    private static final String PACKAGE_NAME = "com.android.stk";
    private static final String MENU_ACTIVITY_NAME =
                                        PACKAGE_NAME + ".StkMenuActivity";
    private static final String INPUT_ACTIVITY_NAME =
                                        PACKAGE_NAME + ".StkInputActivity";

    private static final String SHOW_ALL_APPS = "com.android.launcher.SHOW_ALL_APPS";
    private static final String CLOSE_ALL_APPS = "com.android.launcher.CLOSE_ALL_APPS";

    // Notification id used to display Idle Mode text in NotificationManager.
    private static final int STK_NOTIFICATION_ID = 333;

    // Inner class used for queuing telephony messages (proactive commands,
    // session end) while the service is busy processing a previous message.
    private class DelayedCmd {
        // members
        int id;
        CatCmdMessage msg;

        DelayedCmd(int id, CatCmdMessage msg) {
            this.id = id;
            this.msg = msg;
        }
    }

    private IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid,
                int uid, boolean foregroundActivities) {
            String appName = mContext.getPackageManager().getNameForUid(uid);
            if (foregroundActivities) {
                if (!mAllAppsShown
                        && appName != null && appName.contains("com.android.launcher")) {
                    updateIdleScreenAvailable();
                }
            } else {
                // The browser app must be present in the system as com.android.browser
                // and this works only with the Android default browser package.
                // Note: Needs to be modified to work with other browsers.
                if (appName.contains("com.android.browser")) {
                    if (mStkService != null) {
                        if (mStkService.isEventDownloadActive(
                                EventCode.BROWSER_TERMINATION.value())) {
                            sendBrowserTerminationEvent();
                        }
                    }
                }
            }
        }

        @Override
        public void onImportanceChanged(int pid, int uid, int importance) {
        }

        @Override
        public void onProcessDied(int pid, int uid) {
        }
    };

    // To pass 3GPP Conformance 51.010-4 27.22.4.1.1/2, when apps list is displayed
    // in Home screen, it is considered as BUSY
    private boolean mAllAppsShown = false;
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            CatLog.d(this, "got intent: " + intent);
            String action = intent.getAction();
            if (SHOW_ALL_APPS.equals(action)) {
                mAllAppsShown = true;
            } else if (CLOSE_ALL_APPS.equals(action)) {
                mAllAppsShown = false;
                updateIdleScreenAvailable();
            } else if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(intent.getAction())) {
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                if (stateExtra != null
                        && IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                    try {
                        mActivityManager.unregisterProcessObserver(mProcessObserver);
                    } catch (RemoteException e) {
                        CatLog.d(this, "Error unregistering ProcessObserver");
                    }

                    if (!removeMenu()) {
                        mCurrentMenu = null;
                    }
                    handleSessionEnd();
                    StkAppInstaller.unInstall(mContext);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        // Initialize members
        mCmdsQ = new LinkedList<DelayedCmd>();
        Thread serviceThread = new Thread(null, this, "Stk App Service");
        serviceThread.start();
        mContext = getBaseContext();
        mNotificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        sInstance = this;

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SHOW_ALL_APPS);
        intentFilter.addAction(CLOSE_ALL_APPS);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        registerReceiver(mBroadcastReceiver, intentFilter);

        mActivityManager = ActivityManagerNative.getDefault();
    }

    @Override
    public void onStart(Intent intent, int startId) {

        mStkService = com.android.internal.telephony.cat.CatService
                .getInstance();

        if (mStkService == null) {
            stopSelf();
            CatLog.d(this, " Unable to get Service handle");
            return;
        }

        waitForLooper();
        // onStart() method can be passed a null intent
        // TODO: replace onStart() with onStartCommand()
        if (intent == null) {
            return;
        }

        Bundle args = intent.getExtras();

        if (args == null) {
            return;
        }

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = args.getInt(OPCODE);
        switch(msg.arg1) {
        case OP_CMD:
            msg.obj = args.getParcelable(CMD_MSG);
            break;
        case OP_RESPONSE:
            msg.obj = args;
            /* falls through */
        case OP_LAUNCH_APP:
        case OP_END_SESSION:
        case OP_BOOT_COMPLETED:
        case OP_USER_ACTIVITY:
            break;
        default:
            return;
        }
        mServiceHandler.sendMessage(msg);
    }

    @Override
    public void onDestroy() {
        waitForLooper();
        mServiceLooper.quit();
        unregisterReceiver(mBroadcastReceiver);
        unregisterProcessObserverIfNotNeeded();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void run() {
        Looper.prepare();

        mServiceLooper = Looper.myLooper();
        mServiceHandler = new ServiceHandler();

        Looper.loop();
    }

    /*
     * Package api used by StkMenuActivity to indicate if its on the foreground.
     */
    void indicateMenuVisibility(boolean visibility) {
        mMenuIsVisibile = visibility;
    }

    /*
     * Package api used by StkMenuActivity to get its Menu parameter.
     */
    Menu getMenu() {
        return mCurrentMenu;
    }

    /*
     * Package api used by UI Activities and Dialogs to communicate directly
     * with the service to deliver state information and parameters.
     */
    static StkAppService getInstance() {
        return sInstance;
    }

    private void waitForLooper() {
        while (mServiceHandler == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private final class ServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            int opcode = msg.arg1;

            switch (opcode) {
            case OP_LAUNCH_APP:
                if (mMainCmd == null) {
                    // nothing todo when no SET UP MENU command didn't arrive.
                    return;
                }
                launchMenuActivity(null);
                break;
            case OP_CMD:
                CatCmdMessage cmdMsg = (CatCmdMessage) msg.obj;
                // There are two types of commands:
                // 1. Interactive - user's response is required.
                // 2. Informative - display a message, no interaction with the user.
                //
                // Informative commands can be handled immediately without any delay.
                // Interactive commands can't override each other. So if a command
                // is already in progress, we need to queue the next command until
                // the user has responded or a timeout expired.
                if (!isCmdInteractive(cmdMsg)) {
                    handleCmd(cmdMsg);
                } else {
                    if (!mCmdInProgress) {
                        mCmdInProgress = true;
                        handleCmd((CatCmdMessage) msg.obj);
                    } else {
                        mCmdsQ.addLast(new DelayedCmd(OP_CMD,
                                (CatCmdMessage) msg.obj));
                    }
                }
                break;
            case OP_RESPONSE:
                if (responseNeeded) {
                    handleCmdResponse((Bundle) msg.obj);
                }
                // call delayed commands if needed.
                if (mCmdsQ.size() != 0) {
                    callDelayedMsg();
                } else {
                    mCmdInProgress = false;
                }
                // reset response needed state var to its original value.
                responseNeeded = true;
                break;
            case OP_END_SESSION:
                if (!mCmdInProgress) {
                    mCmdInProgress = true;
                    handleSessionEnd();
                } else {
                    mCmdsQ.addLast(new DelayedCmd(OP_END_SESSION, null));
                }
                break;
            case OP_BOOT_COMPLETED:
                CatLog.d(this, "OP_BOOT_COMPLETED");
                if (mMainCmd == null) {
                    StkAppInstaller.unInstall(mContext);
                }
                break;
            case OP_DELAYED_MSG:
                handleDelayedCmd();
                break;
            case OP_USER_ACTIVITY:
                CatLog.d(this, "OP_USER_ACTIVITY");
                updateUserActivityAvailable();
                break;
            }
        }
    }

    private boolean isCmdInteractive(CatCmdMessage cmd) {
        switch (cmd.getCmdType()) {
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
        case SET_UP_IDLE_MODE_TEXT:
        case SET_UP_MENU:
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
        case SET_UP_EVENT_LIST:
            return false;
        }

        return true;
    }

    private void handleDelayedCmd() {
        if (mCmdsQ.size() != 0) {
            DelayedCmd cmd = mCmdsQ.poll();
            switch (cmd.id) {
            case OP_CMD:
                handleCmd(cmd.msg);
                break;
            case OP_END_SESSION:
                handleSessionEnd();
                break;
            }
        }
    }

    private void callDelayedMsg() {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_DELAYED_MSG;
        mServiceHandler.sendMessage(msg);
    }

    private void handleSessionEnd() {
        mCurrentCmd = mMainCmd;
        lastSelectedItem = null;
        // In case of SET UP MENU command which removed the app, don't
        // update the current menu member.
        if (mCurrentMenu != null && mMainCmd != null) {
            mCurrentMenu = mMainCmd.getMenu();
        }
        if (mMenuIsVisibile) {
            launchMenuActivity(null);
        }
        if (mCmdsQ.size() != 0) {
            callDelayedMsg();
        } else {
            mCmdInProgress = false;
        }
        // In case a launch browser command was just confirmed, launch that url.
        if (mLaunchBrowser) {
            mLaunchBrowser = false;
            launchBrowser(mBrowserSettings);
        }
    }

    private void handleCmd(CatCmdMessage cmdMsg) {
        if (cmdMsg == null) {
            return;
        }
        // save local reference for state tracking.
        mCurrentCmd = cmdMsg;
        boolean waitForUsersResponse = true;

        // Retrieve the CatService instance if not yet done
        if (mStkService == null) {
            mStkService = com.android.internal.telephony.cat.CatService.getInstance();
        }

        CatLog.d(this, cmdMsg.getCmdType().name());
        switch (cmdMsg.getCmdType()) {
        case DISPLAY_TEXT:
            if (mStkService == null) {
                // If StkService is not yet available, simply returns.
                return;
            }

            TextMessage msg = cmdMsg.geTextMessage();
            responseNeeded = msg.responseNeeded;
            waitForUsersResponse = msg.responseNeeded;
            if (!msg.isHighPriority) {
                if (!isScreenAvailable()) {
                    waitForUsersResponse = false;
                    CatLog.d(this, "display text screen busy");
                    CatResponseMessage resMsg =
                            new CatResponseMessage(mCurrentCmd);
                    resMsg.setResultCode(
                            ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
                    resMsg.setAdditionalInfo(ADDITIONAL_INFO_SCREEN_BUSY);
                    mStkService.onCmdResponse(resMsg);
                    return;
                }
            }

            if (lastSelectedItem != null) {
                msg.title = lastSelectedItem;
            } else if (mMainCmd != null){
                msg.title = mMainCmd.getMenu().title;
            } else {
                // TODO: get the carrier name from the SIM
                msg.title = "";
            }
            launchTextDialog();
            break;
        case SELECT_ITEM:
            mCurrentMenu = cmdMsg.getMenu();
            launchMenuActivity(cmdMsg.getMenu());
            break;
        case SET_UP_MENU:
            mMainCmd = mCurrentCmd;
            mCurrentMenu = cmdMsg.getMenu();
            if (removeMenu()) {
                CatLog.d(this, "Uninstall App");
                mCurrentMenu = null;
                StkAppInstaller.unInstall(mContext);
            } else {
                CatLog.d(this, "Install App");
                StkAppInstaller.install(mContext);
            }
            if (mMenuIsVisibile) {
                launchMenuActivity(null);
            }
            break;
        case GET_INPUT:
        case GET_INKEY:
            launchInputActivity();
            break;
        case SET_UP_IDLE_MODE_TEXT:
            waitForUsersResponse = false;
            launchIdleText();
            break;
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
            waitForUsersResponse = false;
            launchEventMessage();
            break;
        case LAUNCH_BROWSER:
            launchConfirmationDialog(mCurrentCmd.geTextMessage());
            break;
        case SET_UP_CALL:
            launchConfirmationDialog(mCurrentCmd.getCallSettings().confirmMsg);
            break;
        case PLAY_TONE:
            launchToneDialog();
            break;
        case OPEN_CHANNEL:
            launchConfirmationDialog(mCurrentCmd.geTextMessage());
            break;
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            TextMessage m = mCurrentCmd.geTextMessage();

            if ((m != null) && (m.text == null)) {
                switch(cmdMsg.getCmdType()) {
                case CLOSE_CHANNEL:
                    m.text = getResources().getString(R.string.default_close_channel_msg);
                    break;
                case RECEIVE_DATA:
                    m.text = getResources().getString(R.string.default_receive_data_msg);
                    break;
                case SEND_DATA:
                    m.text = getResources().getString(R.string.default_send_data_msg);
                    break;
                }
            }
            launchTransientEventMessage();
            break;
        case SET_UP_EVENT_LIST:
            waitForUsersResponse = false;
            processSetupEventList();
            break;
        }

        if (!waitForUsersResponse) {
            if (mCmdsQ.size() != 0) {
                callDelayedMsg();
            } else {
                mCmdInProgress = false;
            }
        }
    }

    private void handleCmdResponse(Bundle args) {
        if (mCurrentCmd == null) {
            return;
        }
        if (mStkService == null) {
            mStkService = com.android.internal.telephony.cat.CatService.getInstance();
            if (mStkService == null) {
                // This should never happen (we should be responding only to a message
                // that arrived from StkService). It has to exist by this time
                throw new RuntimeException("mStkService is null when we need to send response");
            }
        }

        CatResponseMessage resMsg = new CatResponseMessage(mCurrentCmd);

        // set result code
        boolean helpRequired = args.getBoolean(HELP, false);

        switch(args.getInt(RES_ID)) {
        case RES_ID_MENU_SELECTION:
            CatLog.d(this, "RES_ID_MENU_SELECTION");
            int menuSelection = args.getInt(MENU_SELECTION);
            switch(mCurrentCmd.getCmdType()) {
            case SET_UP_MENU:
            case SELECT_ITEM:
                lastSelectedItem = getItemName(menuSelection);
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(ResultCode.OK);
                }
                resMsg.setMenuSelection(menuSelection);
                break;
            }
            break;
        case RES_ID_INPUT:
            CatLog.d(this, "RES_ID_INPUT");
            String input = args.getString(INPUT);
            Input cmdInput = mCurrentCmd.geInput();
            if (cmdInput != null && cmdInput.yesNo) {
                boolean yesNoSelection = input
                        .equals(StkInputActivity.YES_STR_RESPONSE);
                resMsg.setYesNo(yesNoSelection);
            } else {
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(ResultCode.OK);
                    resMsg.setInput(input);
                }
            }
            break;
        case RES_ID_CONFIRM:
            CatLog.d(this, "RES_ID_CONFIRM");
            boolean confirmed = args.getBoolean(CONFIRMATION);
            switch (mCurrentCmd.getCmdType()) {
            case DISPLAY_TEXT:
                resMsg.setResultCode(confirmed ? ResultCode.OK
                        : ResultCode.UICC_SESSION_TERM_BY_USER);
                break;
            case LAUNCH_BROWSER:
                if (confirmed) {
                    mLaunchBrowser = true;
                    mBrowserSettings = mCurrentCmd.getBrowserSettings();
                }
                /* If the proactive command "Launch browser: if not already
                 * launched" is received and the browser is already launched
                 * then the result code LAUNCH_BROWSER_ERROR and additional
                 * info STK_BROWSER_UNAVAILABLE will be sent back to SIM.
                 */
                if ((mCurrentCmd.getBrowserSettings().mode ==
                        LaunchBrowserMode.LAUNCH_IF_NOT_ALREADY_LAUNCHED)
                        && confirmed
                        && isBrowserAlreadyLaunched()) {
                    resMsg.setResultCode(ResultCode.LAUNCH_BROWSER_ERROR);
                    resMsg.setAdditionalInfo(STK_BROWSER_UNAVAILABLE);
                    CatLog.d(this, "LAUNCH_BROWSER_ERROR - BROWSER UNAVAILABLE");
                    mLaunchBrowser = false;
                } else {
                    resMsg.setResultCode(confirmed ? ResultCode.OK
                            : ResultCode.UICC_SESSION_TERM_BY_USER);
                }
                break;
            case SET_UP_CALL:
                resMsg.setResultCode(ResultCode.OK);
                resMsg.setConfirmation(confirmed);
                if (confirmed) {
                    launchEventMessage(mCurrentCmd.getCallSettings().callMsg);
                }
                break;
            case OPEN_CHANNEL:
                resMsg.setResultCode(
                        confirmed ? ResultCode.OK : ResultCode.USER_NOT_ACCEPT);
                resMsg.setConfirmation(confirmed);
                break;
            }
            break;
        case RES_ID_DONE:
            resMsg.setResultCode(ResultCode.OK);
            break;
        case RES_ID_BACKWARD:
            CatLog.d(this, "RES_ID_BACKWARD");
            resMsg.setResultCode(ResultCode.BACKWARD_MOVE_BY_USER);
            break;
        case RES_ID_END_SESSION:
            CatLog.d(this, "RES_ID_END_SESSION");
            resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
            break;
        case RES_ID_TIMEOUT:
            CatLog.d(this, "RES_ID_TIMEOUT");
            // GCF test-case 27.22.4.1.1 Expected Sequence 1.5 (DISPLAY TEXT,
            // Clear message after delay, successful) expects result code OK.
            // If the command qualifier specifies no user response is required
            // then send OK instead of NO_RESPONSE_FROM_USER
            if ((mCurrentCmd.getCmdType().value() == AppInterface.CommandType.DISPLAY_TEXT
                    .value())
                    && (mCurrentCmd.geTextMessage().userClear == false)) {
                resMsg.setResultCode(ResultCode.OK);
            } else {
                resMsg.setResultCode(ResultCode.NO_RESPONSE_FROM_USER);
            }
            break;
        case RES_ID_CHOICE:
            int choice = args.getInt(CHOICE);
            CatLog.d(this, "User Choice=" + choice);
            switch (choice) {
                case YES:
                    resMsg.setResultCode(ResultCode.OK);
                    break;
                case NO:
                    resMsg.setResultCode(ResultCode.USER_NOT_ACCEPT);
                    break;
            }
            break;
        default:
            CatLog.d(this, "Unknown result id");
            return;
        }
        mStkService.onCmdResponse(resMsg);
    }

    private boolean isBrowserAlreadyLaunched() {
        // Maximum running tasks.
        // Note: setting MAX_TASKS to 50 is far enough.
        final int MAX_TASKS = 50;
        ActivityManager activityManager = (ActivityManager) mContext
                .getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }
        List<RunningTaskInfo> runningTaskInfoList = activityManager.getRunningTasks(MAX_TASKS);
        if (runningTaskInfoList != null) {
            Iterator<RunningTaskInfo> runningTaskInfoIterator = runningTaskInfoList.iterator();
            while (runningTaskInfoIterator.hasNext()) {
                RunningTaskInfo runningTaskInfo = runningTaskInfoIterator.next();
                if (runningTaskInfo != null) {
                    ComponentName runningTaskComponent = runningTaskInfo.baseActivity;
                    CatLog.d(this, runningTaskComponent.getClassName());
                    if ("com.android.browser.BrowserActivity".equals(
                        runningTaskComponent.getClassName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns 0 or FLAG_ACTIVITY_NO_USER_ACTION, 0 means the user initiated the action.
     *
     * @param userAction If the userAction is yes then we always return 0 otherwise
     * mMenuIsVisible is used to determine what to return. If mMenuIsVisible is true
     * then we are the foreground app and we'll return 0 as from our perspective a
     * user action did cause. If it's false than we aren't the foreground app and
     * FLAG_ACTIVITY_NO_USER_ACTION is returned.
     *
     * @return 0 or FLAG_ACTIVITY_NO_USER_ACTION
     */
    private int getFlagActivityNoUserAction(InitiatedByUserAction userAction) {
        return ((userAction == InitiatedByUserAction.yes) | mMenuIsVisibile) ?
                                                    0 : Intent.FLAG_ACTIVITY_NO_USER_ACTION;
    }

    private void launchMenuActivity(Menu menu) {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        newIntent.setClassName(PACKAGE_NAME, MENU_ACTIVITY_NAME);
        int intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        if (menu == null) {
            // We assume this was initiated by the user pressing the tool kit icon
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.yes);

            newIntent.putExtra("STATE", StkMenuActivity.STATE_MAIN);
        } else {
            // We don't know and we'll let getFlagActivityNoUserAction decide.
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.unknown);

            newIntent.putExtra("STATE", StkMenuActivity.STATE_SECONDARY);
        }
        newIntent.setFlags(intentFlags);
        mContext.startActivity(newIntent);
    }

    private void launchInputActivity() {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.setClassName(PACKAGE_NAME, INPUT_ACTIVITY_NAME);
        newIntent.putExtra("INPUT", mCurrentCmd.geInput());
        mContext.startActivity(newIntent);
    }

    private void launchTextDialog() {
        Intent newIntent = new Intent(this, StkDialogActivity.class);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.putExtra("TEXT", mCurrentCmd.geTextMessage());
        startActivity(newIntent);
    }

    private void launchEventMessage() {
        launchEventMessage(mCurrentCmd.geTextMessage());
    }

    private void launchEventMessage(TextMessage msg) {
        if (msg == null || msg.text == null) {
            return;
        }
        Toast toast = new Toast(mContext.getApplicationContext());
        LayoutInflater inflate = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflate.inflate(R.layout.stk_event_msg, null);
        TextView tv = (TextView) v
                .findViewById(com.android.internal.R.id.message);
        ImageView iv = (ImageView) v
                .findViewById(com.android.internal.R.id.icon);
        if (msg.icon != null) {
            iv.setImageBitmap(msg.icon);
        } else {
            iv.setVisibility(View.GONE);
        }
        if (!msg.iconSelfExplanatory) {
            tv.setText(msg.text);
        }

        toast.setView(v);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }

    private void launchConfirmationDialog(TextMessage msg) {
        msg.title = lastSelectedItem;
        Intent newIntent = new Intent(this, StkDialogActivity.class);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.putExtra("TEXT", msg);
        startActivity(newIntent);
    }

    private void launchBrowser(BrowserSettings settings) {
        if (settings == null) {
            return;
        }

        // to launch home page, make sure that data Uri is null.
        Uri data = null;

        if (settings.url != null) {
            CatLog.d(this, "settings.url = " + settings.url);
            if ((settings.url.startsWith("http://") || (settings.url.startsWith("https://")))) {
                data = Uri.parse(settings.url);
            } else {
                String modifiedUrl = "http://" + settings.url;
                CatLog.d(this, "modifiedUrl = " + modifiedUrl);
                data = Uri.parse(modifiedUrl);
            }
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, data);
        intent.setClassName("com.android.browser", "com.android.browser.BrowserActivity");
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        switch (settings.mode) {
        case USE_EXISTING_BROWSER:
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        case LAUNCH_NEW_BROWSER:
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            break;
        case LAUNCH_IF_NOT_ALREADY_LAUNCHED:
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        default:
            CatLog.d(this, "Unknown launch browser setting mode " + settings.mode);
        }
        // start browser activity
        startActivity(intent);
        // a small delay, let the browser start, before processing the next command.
        // this is good for scenarios where a related DISPLAY TEXT command is
        // followed immediately.
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {}
    }

    private void launchIdleText() {
        TextMessage msg = mCurrentCmd.geTextMessage();

        if (msg == null) {
            CatLog.d(this, "mCurrent.getTextMessage is NULL");
            mNotificationManager.cancel(STK_NOTIFICATION_ID);
            return;
        }
        if (msg.text == null) {
            mNotificationManager.cancel(STK_NOTIFICATION_ID);
        } else {
            PendingIntent pendingIntent = PendingIntent.getService(mContext, 0,
                    new Intent(mContext, StkAppService.class), 0);

            final Notification.Builder notificationBuilder = new Notification.Builder(
                    StkAppService.this);
            notificationBuilder.setContentTitle("");
            notificationBuilder
                    .setSmallIcon(com.android.internal.R.drawable.stat_notify_sim_toolkit);
            notificationBuilder.setContentIntent(pendingIntent);
            notificationBuilder.setOngoing(true);
            // Set text and icon for the status bar and notification body.
            if (!msg.iconSelfExplanatory) {
                notificationBuilder.setContentText(msg.text);
            }
            if (msg.icon != null) {
                notificationBuilder.setLargeIcon(msg.icon);
            } else {
                Bitmap bitmapIcon = BitmapFactory.decodeResource(StkAppService.this
                    .getResources().getSystem(),
                    com.android.internal.R.drawable.stat_notify_sim_toolkit);
                notificationBuilder.setLargeIcon(bitmapIcon);
            }

            mNotificationManager.notify(STK_NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void launchToneDialog() {
        Intent newIntent = new Intent(this, ToneDialog.class);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.putExtra("TEXT", mCurrentCmd.geTextMessage());
        newIntent.putExtra("TONE", mCurrentCmd.getToneSettings());
        startActivity(newIntent);
    }

    private void launchOpenChannelDialog() {
        TextMessage msg = mCurrentCmd.geTextMessage();
        if (msg == null) {
            CatLog.d(this, "msg is null, return here");
            return;
        }

        msg.title = getResources().getString(R.string.stk_dialog_title);
        if (msg.text == null) {
            msg.text = getResources().getString(R.string.default_open_channel_msg);
        }

        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(msg.title)
                    .setMessage(msg.text)
                    .setCancelable(false)
                    .setPositiveButton(getResources().getString(R.string.stk_dialog_accept),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Bundle args = new Bundle();
                            args.putInt(RES_ID, RES_ID_CHOICE);
                            args.putInt(CHOICE, YES);
                            Message message = mServiceHandler.obtainMessage();
                            message.arg1 = OP_RESPONSE;
                            message.obj = args;
                            mServiceHandler.sendMessage(message);
                        }
                    })
                    .setNegativeButton(getResources().getString(R.string.stk_dialog_reject),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Bundle args = new Bundle();
                            args.putInt(RES_ID, RES_ID_CHOICE);
                            args.putInt(CHOICE, NO);
                            Message message = mServiceHandler.obtainMessage();
                            message.arg1 = OP_RESPONSE;
                            message.obj = args;
                            mServiceHandler.sendMessage(message);
                        }
                    })
                    .create();

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        dialog.show();
    }

    private void launchTransientEventMessage() {
        TextMessage msg = mCurrentCmd.geTextMessage();
        if (msg == null) {
            CatLog.d(this, "msg is null, return here");
            return;
        }

        msg.title = getResources().getString(R.string.stk_dialog_title);

        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(msg.title)
                    .setMessage(msg.text)
                    .setCancelable(false)
                    .setPositiveButton(getResources().getString(android.R.string.ok),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    })
                    .create();

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        dialog.show();
    }

    private String getItemName(int itemId) {
        Menu menu = mCurrentCmd.getMenu();
        if (menu == null) {
            return null;
        }
        for (Item item : menu.items) {
            if (item.id == itemId) {
                return item.text;
            }
        }
        return null;
    }

    private boolean removeMenu() {
        try {
            if (mCurrentMenu.items.size() == 1 &&
                mCurrentMenu.items.get(0) == null) {
                return true;
            }
        } catch (NullPointerException e) {
            CatLog.d(this, "Unable to get Menu's items size");
            return true;
        }
        return false;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mStkService != null) {
            String lang = SystemProperties.get("persist.sys.language");
            if (lang != null && lang.length() == 2) {
                try {
                    byte[] additionalInfo = {
                            (byte)ComprehensionTlvTag.LANGUAGE.value(),
                            0x02, // Language length
                            // Language Code - Pair of alpha-numeric characters
                            GsmAlphabet.stringToGsm7BitPacked(lang.substring(0, 1))[1],
                            GsmAlphabet.stringToGsm7BitPacked(lang.substring(1, 2))[1]};
                    mStkService.onEventDownload(new CatEventMessage(
                            EventCode.LANGUAGE_SELECTION.value(), additionalInfo, true));
                } catch (EncodeException e) {
                    CatLog.d(this, "Event Download Language selection Encode error "+ e);
                }
            }
        }
    }

    private boolean isScreenAvailable() {
        ActivityManager am = (ActivityManager)mContext
                .getSystemService(Activity.ACTIVITY_SERVICE);
        boolean screenAvailable = false;

        if (am == null) {
            return screenAvailable;
        }

        List<RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (tasks != null && tasks.size() > 0) {
            String packName = tasks.get(0).topActivity.getPackageName();
            if (packName.equals(PACKAGE_NAME)) {
                screenAvailable = true;
                CatLog.d(this, "stk on top");
            } else {
                PackageManager pm = getPackageManager();
                Intent homeIntent = new Intent(Intent.ACTION_MAIN, null);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                List<ResolveInfo> mApps;
                mApps = pm.queryIntentActivities(homeIntent, 0);

                for (ResolveInfo info : mApps) {
                    ActivityInfo activity = info.activityInfo;
                    if (activity.packageName.equals(packName)) {
                        if (!mAllAppsShown) {
                            screenAvailable = true;
                            CatLog.d(this, "home on top");
                        }
                        break;
                    }
                }
            }
        }

        return screenAvailable;
    }

    private void processSetupEventList() {
        boolean registerProcessObserver;

        if (mStkService != null) {
            registerProcessObserver = false;
            if (mStkService.isEventDownloadActive(EventCode.USER_ACTIVITY.value())) {
                registerForUserActivityIfNeeded(true);
            }

            if (mStkService.isEventDownloadActive(EventCode.IDLE_SCREEN_AVAILABLE.value())) {
                registerProcessObserver = true;
            }

            if (mStkService.isEventDownloadActive(EventCode.BROWSER_TERMINATION.value())) {
                registerProcessObserver = true;
            }
        } else {
            registerProcessObserver = false;
        }

        try {
            if (registerProcessObserver) {
                mActivityManager.registerProcessObserver(mProcessObserver);
            } else {
                mActivityManager.unregisterProcessObserver(mProcessObserver);
            }
        } catch (RemoteException e) {
            CatLog.d(this, "Error registering/unregistering ProcessObserver");
        }
    }

    private void unregisterProcessObserverIfNotNeeded() {
        boolean unregisterProcessObserver;
        if (mStkService != null) {
            if (mStkService.isEventDownloadActive(EventCode.IDLE_SCREEN_AVAILABLE.value())
                    || mStkService.isEventDownloadActive(
                            EventCode.BROWSER_TERMINATION.value())) {
                unregisterProcessObserver = false;
            } else {
                unregisterProcessObserver = true;
            }
        } else {
            unregisterProcessObserver = true;
        }

        if (unregisterProcessObserver) {
            try {
                mActivityManager.unregisterProcessObserver(mProcessObserver);
            } catch (RemoteException e) {
                CatLog.d(this, "Error unregistering ProcessObserver");
            }
        }
    }

    private void updateIdleScreenAvailable() {
        if (mStkService != null) {
            mStkService.onEventDownload(new CatEventMessage(
                    EventCode.IDLE_SCREEN_AVAILABLE.value(),
                    com.android.internal.telephony.cat.CatService.DEV_ID_DISPLAY,
                    com.android.internal.telephony.cat.CatService.DEV_ID_UICC, null, true));
        }

        unregisterProcessObserverIfNotNeeded();
    }

    private void updateUserActivityAvailable() {
        if (mStkService != null) {
            mStkService.onEventDownload(new CatEventMessage(
                    EventCode.USER_ACTIVITY.value(), null, true));
            registerForUserActivityIfNeeded(false);
        }
    }

    private void registerForUserActivityIfNeeded(boolean status) {
        Intent launcherIntent = new Intent(AppInterface.CHECK_USER_ACTIVITY_ACTION);
        launcherIntent.putExtra("STK_USER_ACTIVITY_REQUEST", status);
        sendBroadcast(launcherIntent);
    }

    private void sendBrowserTerminationEvent() {
        if (mStkService != null) {
            byte[] additionalInfo = {
                    (byte)ComprehensionTlvTag.BROWSER_TERMINATION_CAUSE.value(),
                    0x01, // length
                    0x00 // user termination
                    };

            mStkService.onEventDownload(new CatEventMessage(
                    EventCode.BROWSER_TERMINATION.value(),
                    additionalInfo, false));
        }
    }
}
