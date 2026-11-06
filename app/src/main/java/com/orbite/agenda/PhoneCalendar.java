package com.orbite.agenda;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Lecture de l'agenda du téléphone : tous les comptes synchronisés
 * (Google, Xiaomi, Outlook, Samsung…) et les agendas locaux.
 * Les événements récurrents sont déjà dépliés en occurrences par Android.
 * Rien n'est modifié dans l'agenda du téléphone.
 */
final class PhoneCalendar {

    static final int MAX_EVENTS = 10000;

    private PhoneCalendar() { }

    static String read(ContentResolver cr, long from, long to) {
        try {
            JSONArray cals = new JSONArray();
            String[] cp = {
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.VISIBLE
            };
            try (Cursor c = cr.query(CalendarContract.Calendars.CONTENT_URI, cp, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        JSONObject o = new JSONObject();
                        o.put("id", c.getLong(0));
                        o.put("name", c.isNull(1) ? "" : c.getString(1));
                        o.put("account", c.isNull(2) ? "" : c.getString(2));
                        o.put("color", hex(c.isNull(3) ? 0xFF4CC9F0 : c.getInt(3)));
                        o.put("visible", c.isNull(4) ? 1 : c.getInt(4));
                        cals.put(o);
                    }
                }
            }

            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, from);
            ContentUris.appendId(b, to);
            String[] ip = {
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.DISPLAY_COLOR,
                    CalendarContract.Instances.STATUS
            };
            JSONArray evs = new JSONArray();
            int n = 0;
            boolean more = false;
            try (Cursor c = cr.query(b.build(), ip, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (n >= MAX_EVENTS) { more = true; break; }
                        JSONObject o = new JSONObject();
                        o.put("eid", c.getLong(0));
                        o.put("cal", c.getLong(1));
                        o.put("title", c.isNull(2) ? "" : c.getString(2));
                        o.put("begin", c.getLong(3));
                        o.put("end", c.isNull(4) ? c.getLong(3) : c.getLong(4));
                        o.put("allDay", c.isNull(5) ? 0 : c.getInt(5));
                        o.put("location", c.isNull(6) ? "" : c.getString(6));
                        o.put("description", c.isNull(7) ? "" : c.getString(7));
                        if (!c.isNull(8)) o.put("color", hex(c.getInt(8)));
                        o.put("status", c.isNull(9) ? 0 : c.getInt(9));
                        evs.put(o);
                        n++;
                    }
                }
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("calendars", cals);
            out.put("events", evs);
            out.put("truncated", more);
            return out.toString();
        } catch (SecurityException e) {
            return error("permission");
        } catch (Exception e) {
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String error(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"json\"}";
        }
    }

    private static String hex(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }
}
