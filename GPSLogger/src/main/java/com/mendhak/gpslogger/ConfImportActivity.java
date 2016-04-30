/*
*    This file is part of GPSLogger for Android.
*
*    GPSLogger for Android is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    GPSLogger for Android is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
*/


package com.mendhak.gpslogger;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;
//import com.android.email.Email;
//import com.android.email.provider.AttachmentProvider;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.MenuItem;
import com.mendhak.gpslogger.common.PrefsIO;
import com.mendhak.gpslogger.common.Utilities;

import net.kataplop.gpslogger.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ConfImportActivity extends SherlockActivity
{

    String wholeFile = null;

    public static class  Columns {
        public static final String _ID = "_id";
        public static final String DATA = "_data";
        public static final String DISPLAY_NAME = "_display_name";
        public static final String SIZE = "_size";
    }

    /**
     * Event raised when the form is created for the first time
     */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {

        Utilities.LogDebug("ConfImportActivity.onCreate");

        super.onCreate(savedInstanceState);

        Uri uri = getIntent().getData();
        String path = "";
        String str = "";
//        String typemime = "";
        String scheme = "";
        File mySetFile = null;

        wholeFile = "";

        if(uri!=null) {
//            getIntent().setData(null);
            scheme = uri.getScheme();
            if (scheme.equals(ContentResolver.SCHEME_CONTENT)) {
                    ContentResolver cr = getContentResolver();
//                    ContentProviderClient cpc = cr.acquireContentProviderClient(uri);
//                    typemime = cr.getType(uri);
                    Uri attachmentUri = null;
                    Cursor c = cr.query(uri, new String[] { Columns.DATA }, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                String struri=c.getString(0);
                                if(struri!=null) {
                                    attachmentUri = Uri.parse(struri);
                                    int imax = c.getCount();
                                    int jmax = c.getColumnCount();
                                    for (int i = 0; i < imax; i++) {
                                        if (c.moveToNext()) str = c.getString(0);
                                    }
                                }
                                else attachmentUri = null;
                            }
                            else attachmentUri = null;

                        } finally {
                            c.close();
                        }
                    }
                    if(attachmentUri != null) {
                        uri=attachmentUri;
                        scheme = uri.getScheme();
                    }
            }
            if(scheme==null) {
                path = uri.getPath();
//                fileName = uri.getLastPathSegment();
                if (path != null) {
                    if (path.length() > 0) {
                        mySetFile = new File(path);
                        try {
                            if (mySetFile.exists()) {
                                FileReader fr = new FileReader(mySetFile);
                                BufferedReader br = new BufferedReader(fr);
                                while ((str = br.readLine()) != null) {
                                    wholeFile += (str + "\n");
                                }
                                br.close();
//                    Intent settingsActivity = new Intent(context, GpsSettingsActivity.class);
//                    context.startActivity(settingsActivity);
                            } else
                                Toast.makeText(this, R.string.confimport_show_failed, Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            Toast.makeText(this, "Exception: " + t.toString(), Toast.LENGTH_LONG).show();
                            Toast.makeText(this, R.string.confimport_show_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
            else if (scheme.equals(ContentResolver.SCHEME_CONTENT)) {
                ContentResolver cr = getContentResolver();
                try {
                    AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
                    long length = afd.getLength();
                    byte[] filedata = new byte[(int) length];
                    InputStream is = cr.openInputStream(uri);
                    if(is != null)
                    try {
                        is.read(filedata, 0, (int) length);
                        wholeFile += new String(filedata,"UTF8");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
            else if (scheme.equals(ContentResolver.SCHEME_FILE)) {
                path = uri.getPath();
//                fileName = uri.getLastPathSegment();
                if (path != null) {
                    if (path.length() > 0) {
                        mySetFile = new File(path);
                        try {
                            if (mySetFile.exists()) {
                                FileReader fr = new FileReader(mySetFile);
                                BufferedReader br = new BufferedReader(fr);
                                while ((str = br.readLine()) != null) {
                                    wholeFile += (str + "\n");
                                }
                                br.close();
//                    Intent settingsActivity = new Intent(context, GpsSettingsActivity.class);
//                    context.startActivity(settingsActivity);
                            } else
                                Toast.makeText(this, R.string.confimport_show_failed, Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            Toast.makeText(this, "Exception: " + t.toString(), Toast.LENGTH_LONG).show();
                            Toast.makeText(this, R.string.confimport_show_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
            }
        else {
            Toast.makeText(this, R.string.confimport_show_failed, Toast.LENGTH_LONG).show();
        }

        if(wholeFile.length()>0) {
            setContentView(R.layout.confimport);
            TextView tv = (TextView) findViewById(R.id.confimportview);
            tv.setText(wholeFile);
            tv.setMovementMethod(new ScrollingMovementMethod());
        }

    }

    public void onCancelClicked(View view) {
        finish();
    }

    public void onImportClicked(View view) {
        if(wholeFile.length()>0) {
            Intent intent = new Intent(this, GpsMainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(GpsMainActivity.CONF_DATA, wholeFile);
            startActivity(intent);
        }
        finish();
    }

}
