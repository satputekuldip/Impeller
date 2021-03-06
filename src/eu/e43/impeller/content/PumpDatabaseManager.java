package eu.e43.impeller.content;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import eu.e43.impeller.api.Content;
import eu.e43.impeller.R;
import eu.e43.impeller.Utils;
import eu.e43.impeller.account.Authenticator;

/**
 * Created by oshepherd on 29/06/2014.
 */
public class PumpDatabaseManager extends SQLiteOpenHelper {
    private static final String TAG = "PumpDatabaseManager";
    private static final String NOTIFICATION_TAG = "eu.e43.impeller.content.DatabaseUpgrade";

    PumpContentProvider m_context;
    static final int CURRENT_VERSION = 7;

    protected PumpDatabaseManager(PumpContentProvider context) {
        super(context.getContext(), "eu.e43.impeller.content", null, CURRENT_VERSION);
        m_context = context;
        if(Build.VERSION.SDK_INT >= 16)
            setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    private void runQueryFile(SQLiteDatabase db, int resource) {
        String sql;
        try {
            sql = Utils.readAll(m_context.getContext().getResources().openRawResource(resource));

        } catch(IOException e) {
            throw new RuntimeException(e);
        }

        String[] queries = sql.split(";(\\\\s)*[\\n\\r]");
        for(int i = 0; i < queries.length; i++) {
            Log.d(TAG, "Executing query: " + queries[i]);
            db.execSQL(queries[i]);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // We now jump in straight at v4
        runQueryFile(db, R.raw.migrate_v4_start);
        runQueryFile(db, R.raw.migrate_v4_fini);

        onUpgrade(db, 4, CURRENT_VERSION);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Context ctx = m_context.getContext();
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        NotificationCompat.Builder bld = new NotificationCompat.Builder(ctx)
                .setOngoing(true)
                .setContentTitle(ctx.getString(R.string.database_upgrade_title))
                .setContentText(ctx.getString(R.string.database_upgrade_text))
                .setSmallIcon(R.drawable.ic_impeller_wb)
                .setProgress(0, 0, true);
        nm.notify(NOTIFICATION_TAG, 0, bld.build());

        switch(oldVersion) {
            case 1:
                Log.i(TAG, "Performing database migration to v2");
                db.execSQL(
                        "UPDATE activities SET verb=LOWER(verb)");

            case 2:
                Log.i(TAG, "Performing database migration to v3");
                //db.execSQL(
                //        "CREATE INDEX ix_activities_related ON activities (object, verb)");
                //db.execSQL(
                //        "CREATE INDEX ix_objects_inReplyTo ON objects (inReplyTo)");
            case 3:
                Log.i(TAG, "Performing database migration to v4");
                runQueryFile(db, R.raw.migrate_v4_pre);
                runQueryFile(db, R.raw.migrate_v4_start);

                AccountManager am = AccountManager.get(m_context.getContext());
                Account[] accts = am.getAccountsByType(Authenticator.ACCOUNT_TYPE);
                for(Account a : accts) {
                    ContentValues cv = new ContentValues();
                    cv.put("name", a.name);
                    db.insertOrThrow("accounts", null, cv);
                }

                if(accts.length > 0) {
                    runQueryFile(db, R.raw.migrate_v4_xfer);
                }
                runQueryFile(db, R.raw.migrate_v4_fini);

            case 4:
                Log.i(TAG, "Performing database migration to v5");
                db.execSQL("ALTER TABLE recipients ADD COLUMN type SHORT INT");

                // Link up recipients
                Cursor c = db.query("activities LEFT OUTER JOIN objects AS o ON (activities._ID=o._ID)",
                        new String[] { "o._ID", "o.account", "o._json" },
                        null, null, null, null, null);
                try {
                    while(c.moveToNext()) {
                        if(c.getPosition() % 100 == 0) {
                            bld.setProgress(c.getCount(), c.getPosition(), false);
                            nm.notify(NOTIFICATION_TAG, 0, bld.build());
                        }

                        int _id = c.getInt(0);
                        int account = c.getInt(1);
                        String actJSON = c.getString(2);

                        JSONObject act = new JSONObject(actJSON);
                        String[] keys = Content.RECIPIENT_KEYS;
                        for(int i = 0; i < keys.length; i++) {
                            JSONArray list = act.optJSONArray(keys[i]);
                            if(list != null) for(int j = 0; j < list.length(); j++) {
                                JSONObject person = list.getJSONObject(j);
                                int recipient = m_context.ensureObject(db, person, account);

                                ContentValues cv = new ContentValues();
                                cv.put("recipient", recipient);
                                cv.put("activity",  _id);
                                cv.put("type", i);
                                db.insert("recipients", null, cv);
                            }
                        }
                    }
                } catch (JSONException e) {
                    throw new RuntimeException("Bad database contents", e);
                } finally {
                    c.close();
                }

                nm.notify(NOTIFICATION_TAG, 0, bld.setProgress(0, 0, true).build());


            case 5:
                Log.i(TAG, "Performing database migration to v6");
                runQueryFile(db, R.raw.migrate_v6);

            case 6:
                Log.i(TAG, "Performing database migration to v7");
                runQueryFile(db, R.raw.migrate_v7);

                break;
            default:
                throw new RuntimeException("Request to upgrade from " + oldVersion);
        }

        nm.cancel(NOTIFICATION_TAG, 0);
    }
}
