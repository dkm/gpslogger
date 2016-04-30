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

//TODO: Simplify email logic (too many methods)
//TODO: Allow messages in IActionListener callback methods
//TODO: Handle case where a fix is not found and GPS gives up - restart alarm somehow?

package com.mendhak.gpslogger;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Handler;

import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.IActionListener;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.Utilities;
import com.mendhak.gpslogger.loggers.FileLoggerFactory;
import com.mendhak.gpslogger.loggers.IFileLogger;
import com.mendhak.gpslogger.senders.AlarmReceiver;
import com.mendhak.gpslogger.senders.FileSenderFactory;

import net.kataplop.gpslogger.R;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class GpsLoggingService extends Service implements IActionListener
{
    private static NotificationManager gpsNotifyManager;
    private static int NOTIFICATION_ID = 8675309;
    private final static int GPS_LOCATION_REQ_TIMEOUT = 200;
    private final static int ON_LOCATION_CHANGED_MIN_FREQ = 100;
    private final static int NEXT_ALARM_TIME_MIN = 100;
    private final static int MAX_RETRY_START = 10;
    private final static int NEXT_ALARM_PANIC = 5000;   // milliseconds
    private static boolean isRestarting=false;

    private final IBinder mBinder = new GpsLoggingBinder();
    private static IGpsLoggerServiceClient mainServiceClient;

    // ---------------------------------------------------
    // Helpers and managers
    // ---------------------------------------------------
    private static GeneralLocationListener gpsLocationListener;
    private static GeneralLocationListener towerLocationListener;
    LocationManager gpsLocationManager;
    private LocationManager towerLocationManager;
//    private static boolean isStarting = false;

    private Intent alarmIntent;

    AlarmManager nextPointAlarmManager;

    // ---------------------------------------------------

    @Override
    public IBinder onBind(Intent arg0)
    {
        Utilities.LogDebug("GpsLoggingService.onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent arg0)
    {
        Utilities.LogDebug("GpsLoggingService.onUnbind");
        return true;
    }

    @Override
    public void onCreate()
    {
        Utilities.LogDebug("GpsLoggingService.onCreate");
        nextPointAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        Utilities.LogInfo("GPSLoggerService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Utilities.LogDebug("GpsLoggingService.onStartCommand flags="+String.valueOf(flags)+" startId="+String.valueOf(startId));
        long systime = System.currentTimeMillis();

        final Handler handler = new Handler();
        final Intent intt = intent;

        Session.setWaitingForAlarm(false);

        if( ( (flags==START_FLAG_REDELIVERY) || (flags==START_FLAG_RETRY) ) && !Session.isStarted() ) {
/*
            Normally should not happen but sometimes Session object values are lost on restart :(
*/
            Utilities.LogDebug("GpsLoggingService.onStartCommand with redelivery/restart flags and session not started");
            isRestarting=true;
        }

        if( ( flags==START_FLAG_REDELIVERY ) && (startId>(Session.retryStartService+MAX_RETRY_START)) && (systime>(Session.lastPanicRestartService+2*NEXT_ALARM_PANIC)) ) {
/*
            Panic, the system kills our service immediately after starting, then retries to start it
            Normally it should not happen anymore with START_STICKY flag used, but in some race conditions we can suppose such situation
 */
            Utilities.LogDebug("GpsLoggingService Panic!");
            Session.retryStartService = startId;
            final Runnable rp = new Runnable() {
                public void run() {
                    Panic();
                }
            };
            handler.post(rp);
            return START_NOT_STICKY;
        }

        final Runnable r = new Runnable() {
            public void run() {
                HandleIntent(intt);
            }
        };
        handler.post(r);

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        Utilities.LogWarning("GpsLoggingService is being destroyed");
        StopGpsManager();
        mainServiceClient = null;
    }

    @Override
    public void onLowMemory()
    {
        Utilities.LogWarning("Android is low on memory.");
        super.onLowMemory();
    }

/*
    In some cases we need to stop anything and completely restart logging using alarm
*/
    private void Panic()
    {
        Utilities.LogDebug("Panic - trying to stop anything");
        long systime = System.currentTimeMillis();
        StopLogging();
        Session.lastPanicRestartService = systime;
        Intent i = new Intent(this, GpsLoggingService.class);
        i.putExtra("immediate", true);
        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_CANCEL_CURRENT);
        Utilities.LogDebug("GpsLoggingService Setting panic alarm");
        nextPointAlarmManager.set(AlarmManager.RTC_WAKEUP, systime + NEXT_ALARM_PANIC, pi);
        Session.setWaitingForAlarm(true);
        Utilities.LogDebug("GpsLoggingService Stopping self");
        stopSelf();
    }

    private void HandleIntent(Intent intent)
    {

        Utilities.LogDebug("GpsLoggingService.handleIntent");
        GetPreferences();

        Utilities.LogDebug("Null intent? " + String.valueOf(intent == null));

        if (intent != null)
        {
            Bundle bundle = intent.getExtras();
            int flags=intent.getFlags();
            Utilities.LogDebug("GpsLoggingService Intent Flags: "+String.valueOf(flags));
            if (bundle != null)
            {
                boolean stopRightNow = bundle.getBoolean("immediatestop");
                boolean startRightNow = bundle.getBoolean("immediate");
                boolean sendEmailNow = bundle.getBoolean("emailAlarm");
                boolean getNextPoint = bundle.getBoolean("getnextpoint");

                Utilities.LogDebug("stopRightNow - " + String.valueOf(stopRightNow));
                Utilities.LogDebug("startRightNow - " + String.valueOf(startRightNow));
                Utilities.LogDebug("emailAlarm - " + String.valueOf(sendEmailNow));
                Utilities.LogDebug("getNextPoint - " + String.valueOf(getNextPoint));

                if (startRightNow)
                {
                    Utilities.LogInfo("GpsLoggingService Auto starting logging");
                    StartLogging();
                }

                if (stopRightNow)
                {
                    Utilities.LogInfo("GpsLoggingService Auto stop logging");
                    StopLogging();
                }

                if (sendEmailNow)
                {

                    Utilities.LogDebug("GpsLoggingService setReadyToBeAutoSent = true");

                    Session.setReadyToBeAutoSent(true);
                    AutoSendLogFile();
                }

                if (getNextPoint && Session.isStarted())
                {
                    Utilities.LogDebug("GpsLoggingService.HandleIntent - getNextPoint session started");
                    StartGpsManager();
                }
                else if (getNextPoint && isRestarting)
                {
                    Utilities.LogDebug("GpsLoggingService.HandleIntent - getNextPoint isRestarting");
                    isRestarting=false;
                    StartLogging();
                }

            }
        }
        else
        {
            // A null intent is passed in if the service has been killed and
            // restarted.
            Utilities.LogDebug("GpsLoggingService.HandleIntent Service restarted with null intent. Start logging.");
            StartLogging();
        }
        Utilities.LogDebug("GpsLoggingService.HandleIntent Finished");
    }

    @Override
    public void OnComplete()
    {
        Utilities.HideProgress();
    }

    @Override
    public void OnFailure()
    {
        Utilities.HideProgress();
    }

    /**
     * Can be used from calling classes as the go-between for methods and
     * properties.
     */
    public class GpsLoggingBinder extends Binder
    {
        public GpsLoggingService getService()
        {
            Utilities.LogDebug("GpsLoggingBinder.getService");
            return GpsLoggingService.this;
        }
    }

    /**
     * Sets up the auto email timers based on user preferences.
     */
    public void SetupAutoSendTimers()
    {
        Utilities.LogDebug("GpsLoggingService.SetupAutoSendTimers");
        Utilities.LogDebug("isAutoSendEnabled - " + String.valueOf(AppSettings.isAutoSendEnabled()));
        Utilities.LogDebug("Session.getAutoSendDelay - " + String.valueOf(Session.getAutoSendDelay()));
        if (AppSettings.isAutoSendEnabled() && Session.getAutoSendDelay() > 0)
        {
            Utilities.LogDebug("Setting up autosend alarm");
            long triggerTime = System.currentTimeMillis()
                    + (long) (Session.getAutoSendDelay() * 60 * 60 * 1000);

            alarmIntent = new Intent(getApplicationContext(), AlarmReceiver.class);
            CancelAlarm();
            Utilities.LogDebug("New alarm intent");
            PendingIntent sender = PendingIntent.getBroadcast(this, 0, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            am.set(AlarmManager.RTC_WAKEUP, triggerTime, sender);
            Utilities.LogDebug("Alarm has been set");

        }
        else
        {
            Utilities.LogDebug("Checking if alarmIntent is null");
            if (alarmIntent != null)
            {
                Utilities.LogDebug("alarmIntent was null, canceling alarm");
                CancelAlarm();
            }
        }
    }

    private void CancelAlarm()
    {
        Utilities.LogDebug("GpsLoggingService.CancelAlarm");

        if (alarmIntent != null)
        {
            Utilities.LogDebug("GpsLoggingService.CancelAlarm");
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent sender = PendingIntent.getBroadcast(this, 0, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            Utilities.LogDebug("Pending alarm intent was null? " + String.valueOf(sender == null));
            am.cancel(sender);
        }

    }

    /**
     * Method to be called if user has chosen to auto email log files when he
     * stops logging
     */
    private void AutoSendLogFileOnStop()
    {
        Utilities.LogDebug("GpsLoggingService.AutoSendLogFileOnStop");
        Utilities.LogVerbose("isAutoSendEnabled - " + AppSettings.isAutoSendEnabled());
        // autoSendDelay 0 means send it when you stop logging.
        if (AppSettings.isAutoSendEnabled() && Session.getAutoSendDelay() == 0)
        {
            Session.setReadyToBeAutoSent(true);
            AutoSendLogFile();
        }
    }

    /**
     * Calls the Auto Email Helper which processes the file and sends it.
     */
    private void AutoSendLogFile()
    {

        Utilities.LogDebug("GpsLoggingService.AutoSendLogFile");
        Utilities.LogVerbose("isReadyToBeAutoSent - " + Session.isReadyToBeAutoSent());

        // Check that auto emailing is enabled, there's a valid location and
        // file name.
        if (Session.getCurrentFileName() != null && Session.getCurrentFileName().length() > 0
                && Session.isReadyToBeAutoSent() && Session.hasValidLocation())
        {

            //Don't show a progress bar when auto-emailing
            Utilities.LogInfo("Emailing Log File");

            FileSenderFactory.SendFiles(getApplicationContext(), this);
            Session.setReadyToBeAutoSent(true);
            SetupAutoSendTimers();

        }
    }

    protected void ForceEmailLogFile()
    {

        Utilities.LogDebug("GpsLoggingService.ForceEmailLogFile");
        if (AppSettings.isAutoSendEnabled() && Session.getCurrentFileName() != null && Session.getCurrentFileName().length() > 0)
        {
            if (IsMainFormVisible())
            {
                Utilities.ShowProgress(mainServiceClient.GetActivity(), getString(R.string.autosend_sending),
                        getString(R.string.please_wait));
            }

            Utilities.LogInfo("Force emailing Log File");
            FileSenderFactory.SendFiles(getApplicationContext(), this);
        }
    }


    /**
     * Sets the activity form for this service. The activity form needs to
     * implement IGpsLoggerServiceClient.
     *
     * @param mainForm The calling client
     */
    protected static void SetServiceClient(IGpsLoggerServiceClient mainForm)
    {
        Utilities.LogDebug("GpsLoggingService.SetServiceClient");
        mainServiceClient = mainForm;
    }

    /**
     * Gets preferences chosen by the user and populates the AppSettings object.
     * Also sets up email timers if required.
     */
    private void GetPreferences()
    {
        Utilities.LogDebug("GpsLoggingService.GetPreferences");
        Utilities.PopulateAppSettings(getApplicationContext());

        Utilities.LogDebug("Session.getAutoSendDelay: " + Session.getAutoSendDelay());
        Utilities.LogDebug("AppSettings.getAutoSendDelay: " + AppSettings.getAutoSendDelay());

        if (Session.getAutoSendDelay() != AppSettings.getAutoSendDelay())
        {
            Utilities.LogDebug("Old autoSendDelay - " + String.valueOf(Session.getAutoSendDelay())
                    + "; New -" + String.valueOf(AppSettings.getAutoSendDelay()));
            Session.setAutoSendDelay(AppSettings.getAutoSendDelay());
            SetupAutoSendTimers();
        }

    }

    /**
     * Resets the form, resets file name if required, reobtains preferences
     */
    protected void StartLogging()
    {
        Utilities.LogDebug("GpsLoggingService.StartLogging");
        Session.setAddNewTrackSegment(true);

        if (Session.isStarted())
        {
            return;
        }
        Utilities.LogInfo("Starting logging procedures");
        try
        {
            startForeground(NOTIFICATION_ID, new Notification());
        }
        catch (Exception ex)
        {
            System.out.print(ex.getMessage());
        }


        Session.setStarted(true);

        GetPreferences();
        Notify();
        ResetCurrentFileName(true);
        ClearForm();
        Session.setFileLoggers(FileLoggerFactory.GetFileLoggers());

        StartGpsManager();

    }

    /**
     * Asks the main service client to clear its form.
     */
    private void ClearForm()
    {
        if (IsMainFormVisible())
        {
            mainServiceClient.ClearForm();
        }
    }

    /**
     * Stops logging, removes notification, stops GPS manager, stops email timer
     */
    public void StopLogging()
    {
        Utilities.LogDebug("GpsLoggingService.StopLogging");
        Session.setAddNewTrackSegment(true);

        Utilities.LogInfo("Stopping logging");
        closeLoggers();

        Session.setStarted(false);
        StopGpsManager();
        // Email log file before setting location info to null
        AutoSendLogFileOnStop();
        CancelAlarm();
        Session.setCurrentLocationInfo(null);
        stopForeground(true);

        RemoveNotification();
        StopAlarm();
        StopMainActivity();
    }

    /**
     * Manages the notification in the status bar
     */
    private void Notify()
    {

        Utilities.LogDebug("GpsLoggingService.Notify");
        if (AppSettings.shouldShowInNotificationBar())
        {
            gpsNotifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            ShowNotification();
        }
        else
        {
            RemoveNotification();
        }
    }

    /**
     * Hides the notification icon in the status bar if it's visible.
     */
    private void RemoveNotification()
    {
        Utilities.LogDebug("GpsLoggingService.RemoveNotification");
        try
        {
            if (Session.isNotificationVisible())
            {
                gpsNotifyManager.cancelAll();
            }
        }
        catch (Exception ex)
        {
            Utilities.LogError("RemoveNotification", ex);
        }
        finally
        {
            Session.setNotificationVisible(false);
        }
    }

    /**
     * Shows a notification icon in the status bar for GPS Logger
     */
    private void ShowNotification()
    {
        Utilities.LogDebug("GpsLoggingService.ShowNotification");
        // What happens when the notification item is clicked
        Intent contentIntent = new Intent(this, GpsMainActivity.class);
        contentIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pending = PendingIntent.getActivity(getApplicationContext(), 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification nfc = new Notification(R.drawable.gpsloggericon2, null, System.currentTimeMillis());
        nfc.flags |= Notification.FLAG_ONGOING_EVENT;

        NumberFormat nf = new DecimalFormat("###.######");

        String contentText = getString(R.string.gpslogger_still_running);
        if (Session.hasValidLocation())
        {
            contentText = nf.format(Session.getCurrentLatitude()) + ","
                    + nf.format(Session.getCurrentLongitude());
        }

        nfc.setLatestEventInfo(getApplicationContext(), getString(R.string.gpslogger_still_running),
                contentText, pending);

        gpsNotifyManager.notify(NOTIFICATION_ID, nfc);
        Session.setNotificationVisible(true);
    }

    /**
     * Starts the location manager. There are two location managers - GPS and
     * Cell Tower. This code determines which manager to request updates from
     * based on user preference and whichever is enabled. If GPS is enabled on
     * the phone, that is used. But if the user has also specified that they
     * prefer cell towers, then cell towers are used. If neither is enabled,
     * then nothing is requested.
     */
    private void StartGpsManager()
    {
        Utilities.LogDebug("GpsLoggingService.StartGpsManager");

//        GetPreferences(); **** Preferences are updated in HandleIntent function ****

        if (gpsLocationListener == null)
        {
            gpsLocationListener = new GeneralLocationListener(this);
        }

        if (towerLocationListener == null)
        {
            towerLocationListener = new GeneralLocationListener(this);
        }


        gpsLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        towerLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        CheckTowerAndGpsStatus();

        if (Session.isGpsEnabled() && !AppSettings.shouldPreferCellTower())
        {
            Utilities.LogInfo("Requesting GPS location updates");
            // gps satellite based
            gpsLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    GPS_LOCATION_REQ_TIMEOUT, 0,
                    gpsLocationListener);

            gpsLocationManager.addGpsStatusListener(gpsLocationListener);

            Session.setUsingGps(true);
        }
        else if (Session.isTowerEnabled())
        {
            Utilities.LogInfo("Requesting tower location updates");
            Session.setUsingGps(false);
            // Cell tower and wifi based
            towerLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    1000, 0,
                    towerLocationListener);

        }
        else
        {
            Utilities.LogInfo("No provider available");
            Session.setUsingGps(false);
            SetStatus(R.string.gpsprovider_unavailable);
            SetFatalMessage(R.string.gpsprovider_unavailable);
            StopLogging();
            return;
        }

        SetStatus(R.string.started);
    }

    /**
     * This method is called periodically to determine whether the cell tower /
     * gps providers have been enabled, and sets class level variables to those
     * values.
     */
    private void CheckTowerAndGpsStatus()
    {
        Session.setTowerEnabled(towerLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        Session.setGpsEnabled(gpsLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
    }

    /**
     * Stops the location managers
     */
    private void StopGpsManager()
    {

        Utilities.LogDebug("GpsLoggingService.StopGpsManager");

        if ( (towerLocationListener != null) && (towerLocationManager != null) )
        {
            Utilities.LogDebug("Removing towerLocationManager updates");
            towerLocationManager.removeUpdates(towerLocationListener);
        }

        if ( (gpsLocationListener != null) && (gpsLocationManager != null) )
        {
            Utilities.LogDebug("Removing gpsLocationManager updates");
                gpsLocationManager.removeUpdates(gpsLocationListener);
                gpsLocationManager.removeGpsStatusListener(gpsLocationListener);
        }
        Session.setWaitingForAlarm(false);
        SetStatus(getString(R.string.stopped));
    }

    /**
     * Sets the current file name based on user preference.
     */
    private void ResetCurrentFileName(boolean newStart)
    {

        Utilities.LogDebug("GpsLoggingService.ResetCurrentFileName");

        String newFileName = Session.getCurrentFileName();

        if(AppSettings.isStaticFile())
        {
            newFileName = AppSettings.getStaticFileName();
            Session.setCurrentFileName(AppSettings.getStaticFileName());
        }
        else if (AppSettings.shouldCreateNewFileOnceADay())
        {
            // 20100114.gpx
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            newFileName = sdf.format(new Date());
            Session.setCurrentFileName(newFileName);
        }
        else if (newStart)
        {
            // 20100114183329.gpx
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            newFileName = sdf.format(new Date());
            Session.setCurrentFileName(newFileName);
        }

        if (IsMainFormVisible())
        {
            mainServiceClient.onFileName(newFileName);
        }

    }

    /**
     * Gives a status message to the main service client to display
     *
     * @param status The status message
     */
    void SetStatus(String status)
    {
        if (IsMainFormVisible())
        {
            if (mainServiceClient != null){
                mainServiceClient.OnStatusMessage(status);
            } else {
                Utilities.LogDebug("GpsLoggingService.SetStatus: no valid activity bound");
            }
        }
    }

    /**
     * Gives an error message to the main service client to display
     *
     * @param messageId ID of string to lookup
     */
    void SetFatalMessage(int messageId)
    {
        if (IsMainFormVisible())
        {
            mainServiceClient.OnFatalMessage(getString(messageId));
        }
    }

    /**
     * Gets string from given resource ID, passes to SetStatus(String)
     *
     * @param stringId ID of string to lookup
     */
    private void SetStatus(int stringId)
    {
        String s = getString(stringId);
        SetStatus(s);
    }

    /**
     * Notifies main form that logging has stopped (change interface status)
     */
    void StopMainActivity()
    {
        if (IsMainFormVisible())
        {
            mainServiceClient.OnStopLogging();
        }
    }


    /**
     * Stops location manager, then starts it.
     */
    void RestartGpsManagers()
    {
        Utilities.LogDebug("GpsLoggingService.RestartGpsManagers");
        StopGpsManager();
        StartGpsManager();
    }


    /**
     * This event is raised when the GeneralLocationListener has a new location.
     * This method in turn updates notification, writes to file, reobtains
     * preferences, notifies main service client and resets location managers.
     *
     * @param loc Location object
     */
    void OnLocationChanged(Location loc)
    {
        int retryTimeout = Session.getRetryTimeout();

        if (!Session.isStarted())
        {
            Utilities.LogDebug("OnLocationChanged called, but Session.isStarted is false");
            StopLogging();
            return;
        }

        if (Session.isWaitingForAlarm())
        {
            Utilities.LogDebug("OnLocationChanged called, but next alarm time is not arrived");
            return;
        }

        Utilities.LogDebug("GpsLoggingService.OnLocationChanged");

        long currentTimeStamp = System.currentTimeMillis();

        // Wait some time even on 0 frequency so that the UI doesn't lock up

        if ((currentTimeStamp - Session.getLatestTimeStamp()) < ON_LOCATION_CHANGED_MIN_FREQ)
        {
            return;
        }

        // Don't do anything until the user-defined accuracy is reached
        if (AppSettings.getMinimumAccuracyInMeters() > 0)
        {
          if(AppSettings.getMinimumAccuracyInMeters() < Math.abs(loc.getAccuracy()))
            {
                if(retryTimeout < 50)
                {
                    Session.setRetryTimeout(retryTimeout+1);
                    SetStatus("Only accuracy of " + String.valueOf(Math.floor(loc.getAccuracy())) + " reached");
                    StopManagerAndResetAlarm(AppSettings.getRetryInterval());
                    return;
                }
                else
                {
                    Session.setRetryTimeout(0);
                    SetStatus("Only accuracy of " + String.valueOf(Math.floor(loc.getAccuracy())) + " reached and timeout reached");
                    StopManagerAndResetAlarm();
                    return;
                }
            }
        }

        if( NeedToLog(loc) ) {
            Utilities.LogInfo("New location obtained and we need to log a point");
            ResetCurrentFileName(false);
            Session.setLatestTimeStamp(System.currentTimeMillis());
            Session.setCurrentLocationInfo(loc);
            SetDistanceTraveled(loc);
            Notify();
            WriteToFile(loc);
//            GetPreferences(); **** Preferences will be updated in HandleIntent function ****
            if (IsMainFormVisible()) mainServiceClient.OnLocationUpdate(loc);
            }
        StopManagerAndResetAlarm();

    }

    private boolean NeedToLog(Location loc) {
        List<IFileLogger> loggers = Session.getFileLoggers();
        boolean needToLog=false;
        for (IFileLogger logger : loggers)
        {
            needToLog=logger.isTimeToLog();
            if(needToLog) needToLog&=logger.isDistToLog(loc.getLongitude(),loc.getLatitude());
            if(needToLog) return needToLog;
        }
//        Utilities.LogInfo("Need to log? "+String.valueOf(needToLog));
        return needToLog;
    }

    private long GetNextAlarmTime() {
        List<IFileLogger> loggers = Session.getFileLoggers();
        long NextAlarmTime=NEXT_ALARM_TIME_MIN; // Minimum alarm time
        List<Long> nats = new ArrayList<Long>();
        long nat=0;
        long systime = System.currentTimeMillis();
        for (IFileLogger logger : loggers)
        {
            Utilities.LogDebug("GetNextAlarmTime logger:"+logger.getName());
            nat=logger.getNextPointTime();
            Utilities.LogDebug("NextAlarmTime: "+nat);
            nats.add(nat-systime);
        }
        if(!nats.isEmpty()) {
            Long[] arnat = nats.toArray(new Long[nats.size()]);
            Arrays.sort(arnat);
            Utilities.LogDebug("Sorted array of NextAlarmTime: " + Arrays.toString(arnat));
            nat = (long) arnat[0];
            if (nat > NextAlarmTime) NextAlarmTime = nat;
        }
        return NextAlarmTime;
    }

    private void SetDistanceTraveled(Location loc)
    {
        // Distance
        if (Session.getPreviousLocationInfo() == null)
        {
            Session.setPreviousLocationInfo(loc);
        }
        // Calculate this location and the previous location location and add to the current running total distance.
        // NOTE: Should be used in conjunction with 'distance required before logging' for more realistic values.
        double distance = Utilities.CalculateDistance(
                Session.getPreviousLatitude(),
                Session.getPreviousLongitude(),
                loc.getLatitude(),
                loc.getLongitude());
        Session.setPreviousLocationInfo(loc);
        Session.setTotalTravelled(Session.getTotalTravelled() + distance);
    }

    protected void StopManagerAndResetAlarm()
    {
        Utilities.LogDebug("GpsLoggingService.StopManagerAndResetAlarm");
        if( !AppSettings.shouldkeepFix() )
        {
            StopGpsManager();
        }
        SetAlarmForNextPoint();
    }

    protected void StopManagerAndResetAlarm(int retryInterval)
    {
        Utilities.LogDebug("GpsLoggingService.StopManagerAndResetAlarm_retryInterval");
        if( !AppSettings.shouldkeepFix() )
        {
            StopGpsManager();
        }
        SetAlarmForNextPoint(retryInterval);
    }

    private void StopAlarm()
    {
        Utilities.LogDebug("GpsLoggingService.StopAlarm");
        Intent i = new Intent(this, GpsLoggingService.class);
        i.putExtra("getnextpoint", true);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        nextPointAlarmManager.cancel(pi);
    }


    private void SetAlarmForNextPoint()
    {

        Utilities.LogDebug("GpsLoggingService.SetAlarmForNextPoint");

        long systime = System.currentTimeMillis();
        long nextalarm = GetNextAlarmTime();

        Utilities.LogDebug("Systime:" +String.valueOf(systime) + "; Got nextalarm time:" + String.valueOf(nextalarm));

        if(nextalarm <= NEXT_ALARM_TIME_MIN) {  // Do not cancel/set alarm if we need to log immediately
            Utilities.LogDebug("nextalarm is in near future, going to log a point");
            if( !AppSettings.shouldkeepFix() )
            {
                StartGpsManager();
            }
            return;                             // The next point will be logged on next position update
        }

        Intent i = new Intent(this, GpsLoggingService.class);

        i.putExtra("getnextpoint", true);

        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT); // Last argument was 0

        nextPointAlarmManager.cancel(pi);   // Probably not needed as it will be cancelled by the 'set' method

        Utilities.LogDebug("Old alarm cancelled, setting next alarm");

        nextPointAlarmManager.set(AlarmManager.RTC_WAKEUP,
                systime + nextalarm, pi);

        Session.setWaitingForAlarm(true);

    }

    private void SetAlarmForNextPoint(int retryInterval)
    {

        Utilities.LogDebug("GpsLoggingService.SetAlarmForNextPoint_retryInterval");

        Intent i = new Intent(this, GpsLoggingService.class);

        i.putExtra("getnextpoint", true);

        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);  // Last argument was 0
        nextPointAlarmManager.cancel(pi);

        nextPointAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + retryInterval * 1000, pi);

        Session.setWaitingForAlarm(true);

    }

    private void closeLoggers(){
        Utilities.LogDebug("GpsLoggingService.closeLoggers");
        final List<IFileLogger> loggers = Session.getFileLoggers();

        for (IFileLogger logger : loggers)
        {
            try
            {
                logger.close();
            }
            catch (Exception e)
            {
                SetStatus(R.string.could_not_write_to_file);
            }
        }
    }

    /**
     * Calls file helper to write a given location to a file.
     *
     * @param loc Location object
     */
    private void WriteToFile(Location loc)
    {
        Utilities.LogDebug("GpsLoggingService WriteToFile");
        List<IFileLogger> loggers = Session.getFileLoggers();
        Session.setAddNewTrackSegment(false);
        double lon = loc.getLongitude();
        double lat = loc.getLatitude();
        boolean allowdesc = false;
        for (IFileLogger logger : loggers)
        {
            try
            {
                if(logger.isTimeToLog() && logger.isDistToLog(lon,lat))
                {
                    Utilities.LogDebug("Finally write to logger: " + logger.getName() + " at time: " + System.currentTimeMillis());
                    logger.Write(loc);
                    allowdesc=true;
                }
            }
            catch (Exception e)
            {
                SetStatus(R.string.could_not_write_to_file);
            }
            if(allowdesc) Session.setAllowDescription(true);
        }

    }

    /**
     * Informs the main service client of the number of visible satellites.
     *
     * @param count Number of Satellites
     */
    void SetSatelliteInfo(int count)
    {
        if (IsMainFormVisible())
        {
            mainServiceClient.OnSatelliteCount(count);
        }
    }


    private boolean IsMainFormVisible()
    {
        return mainServiceClient != null;
    }


}
