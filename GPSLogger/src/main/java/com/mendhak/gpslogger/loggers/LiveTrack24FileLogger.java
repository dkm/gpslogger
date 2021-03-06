/*
*    This file is part of GPSLogger for Android.
*
*    GPSLogger for Android is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 2 of the License, or
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


/*
 * Copyright Geeksville Industries LLC, a California limited liability corporation.
 * Copyright Marc Poulhiès <dkm@kataplop.net>
 * Code is also published with GPL2
 * http://github.com/geeksville/Gaggle
 */

package com.mendhak.gpslogger.loggers;

import android.location.Location;
import android.os.Handler;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestHandle;
import com.loopj.android.http.RequestParams;
import com.loopj.android.http.SyncHttpClient;
import com.loopj.android.http.TextHttpResponseHandler;
import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.IActionListener;
import com.mendhak.gpslogger.common.Utilities;
import com.mendhak.gpslogger.loggers.utils.LocationBuffer;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;


public class LiveTrack24FileLogger extends AbstractLiveLogger {

    public static final String name = "LIVETRACK24";

    /**
     * Sent to server
     */
    private String ourVersion;

    /**
     * How many secs between position reports
     */
    private int expectedIntervalSecs;

    /**
     * FIXME, get the real phone name
     */
    private String phoneName = android.os.Build.MODEL;

    /**
     * Our app name, FIXME - pick a good name
     */
    private String programName = "Flight GPSLogger";

    /**
     * FIXME - pass in from app
     */
    private String vehicleName;

    /**
     * Hardwired for paraglider - FIXME
     */
    private int vehicleType = 1;

    /**
     * Claim built in for now
     */
    private String gpsType = "Internal GPS";

    private String userName;
    private String password;
    private final String serverURL;

    private String trackURL, clientURL;

    private String sessionId = null; //(new Random()).nextInt(0x7fffffff);
    private boolean login_ok = false;
    private boolean start_ok = false;

    private int packetNum = 1;
//    long lastUpdateTime = SystemClock.elapsedRealtime();*****goes to superclass

    private static LiveTrack24FileLogger instance = null;
    private final static int POINT_MAX_RETRIES = 1;
    private final static int POINT_RETRIES_TIMEOUT = 200; // ms
    private final static int POINT_TIMEOUT = 3000;    // ms
    private final static int LOGIN_MAX_RETRIES = 3;
    private final static int LOGIN_RETRIES_TIMEOUT = 500; // ms
    private final static int LOGIN_TIMEOUT = 10000;    // ms
//    private final static int RESP_TIMEOUT = 2000;    // ms

    private final static int PACKET_START = 2;
    private final static int PACKET_END = 3;
    private final static int PACKET_POINT = 4;

    static SyncHttpClient httpSyncClient = new SyncHttpClient();
    static AsyncHttpClient httpAsyncClient = new AsyncHttpClient();

    private boolean lastSendingOK = false;


    public boolean liveUpload(LocationBuffer.BufferedLocation b){
        // discard location if login not yet ready.
        if (!login_ok){
            return true;
        }
        lastSendingOK=false;
        startUploadTimer();
        RequestHandle rh = sendLocationPacket(b);
        while( (!rh.isFinished()) && (!isTimedOutUpload()) ) {
            try {
                Thread.sleep(sleepTimeUpload);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return lastSendingOK;
    }

    public static LiveTrack24FileLogger getLiveTrack24Logger(String serverURL, String username,
                                                      String password, int expectedInterval, int minDistance)
            throws MalformedURLException {
        if (instance == null ||
                !instance.serverURL.equals(serverURL) ||
                !instance.userName.equals(username) ||
                !instance.password.equals(password) ||
                expectedInterval != instance.expectedIntervalSecs){
            instance = new LiveTrack24FileLogger(serverURL, username, password, expectedInterval, minDistance);
        }

        return instance;
    }

    private LiveTrack24FileLogger(String serverURL, String username,
                                  String password, int expectedInterval, int minDistance) throws MalformedURLException {
        super(expectedInterval,minDistance);
        Utilities.LogDebug("livetrack24 constructor");
        this.ourVersion = AppSettings.getVersionName();
        this.vehicleName = AppSettings.getGliderName();

        this.minbufsize= AppSettings.getALMinBufSize();
//      TODO: better handling of minbufsize and MAX_BUFSIZE is needed - take the both from settings, put some filters on settings
        if(this.minbufsize > this.maxbufsize/2) {
            this.minbufsize = this.maxbufsize/2;
            Utilities.LogDebug("minbufsize set too high, modified value is: " + this.minbufsize);
        }

        if (this.vehicleName == null || this.vehicleName.trim().equals("")){
            this.vehicleName = "none";
        }
        URL url = new URL(serverURL + "/track.php");
        trackURL = url.toString();
        url = new URL(serverURL + "/client.php");
        clientURL = url.toString();
        Utilities.LogDebug("trackURL: "+trackURL);
        Utilities.LogDebug("clientURL: "+clientURL);

        this.userName = username;
        this.password = password;
        this.serverURL = serverURL;

        expectedIntervalSecs = expectedInterval;

        doLogin(); // Login here, so we can find out about bad passwords ASAP
    }

    @Override
    public void closeAfterFlush() {
        // FIXME - add support for end of track types (need retrieve etc...)

        // invalidate login, no more location should be sent to server.
        // Better wait few ms for on-the-fly packet to be transmitted/lost
        login_ok = false;

        HashMap<String,String> m = new HashMap<String, String>();
        m.put("prid", "0");
        sendPacket(PACKET_END, m, locationPacketHandler, true);

        instance = null;
    }

    @Override
    public void Annotate(String description, Location loc) throws Exception {
        // TODO Auto-generated method stub
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Cleans up illegal chars in a URL
     *
     * @param url
     * @return FIXME move
     */
    static String normalizeURL(String url) {
        return url.replace(' ', '+'); // FIXME, do a better job of this
    }

    private AsyncHttpResponseHandler locationPacketHandler = new TextHttpResponseHandler() {
        @Override
        public void onSuccess(int statusCode, org.apache.http.Header[] headers, String message){
            Utilities.LogDebug("livetrack24: packet sent ok:" + message);
            lastSendingOK = true;
        }

        @Override
        public void onFailure(String response, Throwable e){
            Utilities.LogDebug("livetrack24: packet NOT sent:" + response);
            lastSendingOK = false;
        }
    };

    /**
     * Sends a packet to livetrack24 server
     * @param packetType the packet type
     * @param params the request parameters
     * @param async if true, request is asynchronous, else it's synchronous
     * @return the request handle
     */
    RequestHandle sendPacket(int packetType, Map<String,String> params,
                             AsyncHttpResponseHandler handler, boolean async){

        RequestParams nparams = new RequestParams(params);

        nparams.put("leolive", Integer.toString(packetType));
        nparams.put("sid", sessionId);
        nparams.put("pid", Integer.toString(packetNum));
        Utilities.LogDebug("livetrack24: URL: "+trackURL+" nparams: " + nparams.toString());
        packetNum++;

        if (async){
            return httpAsyncClient.get(null, trackURL, nparams, handler);
        } else {
            return httpSyncClient.get(null, trackURL, nparams, handler);
        }
    }

    /**
     * Sends a packet with a location update
     * @param bloc the location
     * @return a request handle for the asynchronous request
     */
    RequestHandle sendLocationPacket(LocationBuffer.BufferedLocation bloc){
        HashMap<String,String> params = new HashMap<String,String>();

        params.put("lat", Float.toString((float)bloc.lat));
        params.put("lon", Float.toString((float)bloc.lon));
        params.put("alt", Integer.toString(bloc.altitude));

	// Was "buffered speed is in m/s, lt24 needs km/h"
        params.put("sog", Float.toString(bloc.speed/(float)3.6));   // Convert to m/s before sending

        params.put("cog", Integer.toString(bloc.bearing));
        params.put("tm", Integer.toString((int)(bloc.timems/1000)));
        httpAsyncClient.setTimeout(POINT_TIMEOUT);
        httpAsyncClient.setMaxRetriesAndTimeout(POINT_MAX_RETRIES, POINT_RETRIES_TIMEOUT);

        return sendPacket(PACKET_POINT, params, locationPacketHandler, false /* async */);
    }

    private TextHttpResponseHandler send_start_handler = new TextHttpResponseHandler() {
        @Override
        public void onSuccess(int statusCode, org.apache.http.Header[] headers, String message){
            Utilities.LogDebug("packet START sent ok:" + message);
            login_ok = true;
        }

        @Override
        public void onFailure(String response, Throwable e){
            Utilities.LogDebug("packet START NOT sent, starting new login...");
            delayedDoLogin(1000);
        }
    };

    private class AsyncLoginHandler extends TextHttpResponseHandler {
        @Override
        public void onSuccess(int statusCode, org.apache.http.Header[] headers, String response) {
            try {
                Utilities.LogDebug("livetrack24: resp for login:" + response);
                final int userID = Integer.parseInt(response);

                if (userID == 0){
                    Utilities.LogDebug("livetrack24: incorrect user/pass");
                    return;
                }

//                if (userID == 0)
//                    throw new Exception("Invalid username or password");
                Utilities.LogDebug("livetrack24: userid=" + userID);
                final Random a = new Random(System.currentTimeMillis());
                int rnd = Math.abs(a.nextInt());
                // we make an int with leftmost bit=1 ,
                // the next 7 bits random
                // (so that the same userID can have multiple active sessions)
                // and the next 3 bytes the userID
                int sid = (rnd & 0x7F000000) | (userID & 0x00ffffff) | 0x80000000;
                sessionId =  Long.toString(sid & 0xFFFFFFFFL);
                Utilities.LogDebug("livetrack24: made a correct session ID " + sessionId);

                HashMap<String, String> params = new HashMap<String, String>();
                params.put("client", programName);
                params.put("v",ourVersion);
                params.put("user",userName);
                params.put("pass",password);
                params.put("phone",phoneName);
                params.put("gps",gpsType);
                params.put("trk1", Integer.toString(expectedIntervalSecs));
                params.put("vtype", Integer.toString(vehicleType));
                params.put("vname",vehicleName);
                httpSyncClient.setTimeout(LOGIN_TIMEOUT);
                httpSyncClient.setMaxRetriesAndTimeout(LOGIN_MAX_RETRIES, LOGIN_RETRIES_TIMEOUT);

                sendPacket(PACKET_START, params, send_start_handler, true /* sync */);
//
//                if (start_ok) {
//                    login_ok = true;
//                } else {
//                    Utilities.LogDebug("livetrack24: start packet not correctly sent, new login try...");
//                    delayedDoLogin(1000);
//                }
            } catch (NumberFormatException ex) {
                Utilities.LogDebug("livetrack24: during login got unexpected answer: "+ response);
                delayedDoLogin(1000);
            }
        }

        @Override
        public void onFailure(String response, Throwable e) {
            Utilities.LogDebug("livetrack24: login response failed");
            delayedDoLogin(1000);
        }
    }

    private void delayedDoLogin(int msecs){
        if (instance == null ){
            Utilities.LogDebug("livetrack24: was trying to login but tracking has stopped before success");
            return;
        }

        Handler handler = new Handler();
        Utilities.LogDebug("livetrack24: will try login in " + msecs + " millisecs");

        Runnable login_retry = new Runnable() {
            @Override
            public void run() {
                Utilities.LogDebug("livetrack24: new try");
                doLogin();
            }
        };
        handler.postDelayed(login_retry, msecs);
    }

    private void doLogin() {
        // If the user has an account on the server then the sessionID must be
        // constructed in the following way to contain the userID
        // First of all your application must get the userID based on the
        // username and password of the user. The url to verify user accounts
        // and get back the userID is
        // http://www.livetrack24.com/client.php?op=login&user=username&pass=pass
        // The username and password are case INSENSITIVE, because on mobile
        // devices it is not easy for all users < ></>o enter the correct case.
        // The result of the page is an integer, 0 if userdata are incorrect, or
        // else the userID of the user

        RequestParams params = new RequestParams();
        params.put("op", "login");
        params.put("user", userName);
        params.put("pass", password);

        httpAsyncClient.setTimeout(LOGIN_TIMEOUT);
        httpAsyncClient.setMaxRetriesAndTimeout(LOGIN_MAX_RETRIES, LOGIN_RETRIES_TIMEOUT);

        Utilities.LogDebug("livetrack24: sending login info");
        httpAsyncClient.get(null, clientURL, params, new AsyncLoginHandler());
    }
}
