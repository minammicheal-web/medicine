package com.mina.medicine;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "Taken" and "Snooze" buttons on a dose notification. */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String act = i.getStringExtra("act");
        String id = i.getStringExtra("id");
        String json = i.getStringExtra("item");
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && id != null) nm.cancel(id.hashCode());
        if ("taken".equals(act)) Reminders.markTaken(c, i.getStringExtra("cid"), i.getLongExtra("dt", 0));
        else if ("snooze".equals(act) && json != null) Reminders.snooze(c, json);
    }
}
