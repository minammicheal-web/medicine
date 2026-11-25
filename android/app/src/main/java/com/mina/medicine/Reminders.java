package com.mina.medicine;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Keeps the list of upcoming dose reminders sent by the page, and always keeps exactly one
 * system alarm set for the earliest of them. When it fires, the notification is shown and the
 * next one is scheduled.
 *
 * Item: {t, id, k ("remind"|"alarm"), title, text, cid, dt}. cid/dt are set for dose reminders,
 * which get "Taken" and "Snooze" buttons.
 */
final class Reminders {
    static final String CH_REMIND = "doses";
    static final String CH_ALARM = "dose_alarms";
    private static final String PREFS = "reminders";
    private static final String KEY = "plan";
    private static final String TAKEN = "taken";

    private Reminders() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray load(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void setPlan(Context c, String json) {
        try {
            new JSONArray(json); // validate
        } catch (Exception e) {
            return;
        }
        prefs(c).edit().putString(KEY, json).apply();
        scheduleNext(c);
    }

    /** Remembers a dose marked as taken from a notification, until the page picks it up. */
    static synchronized void markTaken(Context c, String cid, long dt) {
        if (cid == null || dt <= 0) return;
        try {
            JSONArray a = new JSONArray(prefs(c).getString(TAKEN, "[]"));
            a.put(new JSONObject().put("cid", cid).put("dt", dt).put("at", System.currentTimeMillis()));
            prefs(c).edit().putString(TAKEN, a.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Hands over (and forgets) the doses marked as taken from notifications. */
    static synchronized String pullTaken(Context c) {
        String s = prefs(c).getString(TAKEN, "[]");
        prefs(c).edit().putString(TAKEN, "[]").apply();
        return s;
    }

    /** Shows the same reminder again in 10 minutes. */
    static synchronized void snooze(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            o.put("t", System.currentTimeMillis() + 10 * 60000L);
            o.put("id", o.optString("id") + "-z");
            JSONArray a = load(c);
            a.put(o);
            prefs(c).edit().putString(KEY, a.toString()).apply();
            scheduleNext(c);
        } catch (Exception ignored) { }
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class);
        return PendingIntent.getBroadcast(c, 4242, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 4243, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent action(Context c, String act, JSONObject o) {
        Intent i = new Intent(c, ActionReceiver.class);
        i.putExtra("act", act);
        i.putExtra("id", o.optString("id"));
        i.putExtra("cid", o.optString("cid"));
        i.putExtra("dt", o.optLong("dt", 0));
        i.putExtra("item", o.toString());
        int code = (act + o.optString("id")).hashCode();
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static synchronized void scheduleNext(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c);
        am.cancel(pi);
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        boolean alarm = false;
        JSONArray a = load(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            long t = o.optLong("t", 0);
            if (t > 0 && t < best) {
                best = t;
                alarm = "alarm".equals(o.optString("k"));
            }
        }
        if (best == Long.MAX_VALUE) return;
        if (best < now + 1000) best = now + 1000;
        try {
            if (alarm) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(best, openApp(c)), pi);
            } else if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
        }
    }

    /** Shows everything that is due, drops it from the plan, and schedules the next one. */
    static synchronized void fire(Context c) {
        long now = System.currentTimeMillis();
        JSONArray a = load(c);
        JSONArray keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            long t = o.optLong("t", 0);
            if (t <= now + 30000) {
                boolean alarm = "alarm".equals(o.optString("k"));
                if (now - t <= 2 * 3600000L) show(c, o, alarm); // skip very old ones after the phone was off
            } else {
                keep.put(o);
            }
        }
        prefs(c).edit().putString(KEY, keep.toString()).apply();
        scheduleNext(c);
    }

    private static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel r = new NotificationChannel(CH_REMIND, "Dose reminders", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("A reminder for every dose and at the end of each course");
        r.enableVibration(true);
        nm.createNotificationChannel(r);

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationChannel al = new NotificationChannel(CH_ALARM, "Dose alarms (loud)", NotificationManager.IMPORTANCE_HIGH);
        al.setDescription("Rings like an alarm until you answer it");
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        al.setSound(sound, attrs);
        al.enableVibration(true);
        al.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
        al.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(al);
    }

    @SuppressWarnings("deprecation")
    private static void show(Context c, JSONObject o, boolean alarm) {
        try {
            ensureChannels(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String title = o.optString("title", "Medicine");
            String text = o.optString("text", "");
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, alarm ? CH_ALARM : CH_REMIND);
            else b = new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat_pill)
                    .setColor(0xFF0F766E)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(openApp(c))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            if (!o.optString("cid").isEmpty() && o.optLong("dt", 0) > 0) {
                b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_pill), "✓ Taken", action(c, "taken", o)).build());
                b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_pill), "Snooze 10 min", action(c, "snooze", o)).build());
            }
            if (alarm) {
                b.setCategory(Notification.CATEGORY_ALARM);
                b.setFullScreenIntent(openApp(c), true);
                if (Build.VERSION.SDK_INT >= 26) {
                    b.setTimeoutAfter(3 * 60 * 1000L);
                } else {
                    b.setPriority(Notification.PRIORITY_MAX);
                    b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioManager.STREAM_ALARM);
                    b.setVibrate(new long[]{0, 600, 400, 600, 400, 600});
                }
            } else {
                b.setCategory(Notification.CATEGORY_REMINDER);
                if (Build.VERSION.SDK_INT < 26) {
                    b.setPriority(Notification.PRIORITY_HIGH);
                    b.setDefaults(Notification.DEFAULT_ALL);
                }
            }
            Notification n = b.build();
            if (alarm) n.flags |= Notification.FLAG_INSISTENT; // keep ringing until answered
            nm.notify(o.optString("id", title).hashCode(), n);
        } catch (Exception ignored) {
            // notifications may be switched off by the user
        }
    }
}
