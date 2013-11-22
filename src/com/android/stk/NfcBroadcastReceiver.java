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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.internal.telephony.cat.AppInterface;
import com.android.internal.telephony.cat.CatEventMessage;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.EventCode;

public class NfcBroadcastReceiver extends BroadcastReceiver {

    public void onReceive(Context context, Intent intent) {
        if ("com.nxp.action.CONNECTIVITY_EVENT_DETECTED".equals(intent.getAction())) {
            CatLog.d(this, "NFC --> UICC: HCI Connectivity event detected" );
            AppInterface catService = com.android.internal.telephony.cat.CatService.getInstance();
            if (catService != null) {
                catService.onEventDownload(
                        new CatEventMessage(EventCode.HCI_CONNECTIVITY_EVENT.value(), null, false));
                CatLog.d(this, "Event Download - HCI Connectivty sent to the UICC" );
            } else {
                CatLog.d(this, "Error: CatService getInstance returned null" );
            }
        }
    }
}
