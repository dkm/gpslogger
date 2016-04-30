package com.mendhak.gpslogger.loggers;

import android.annotation.SuppressLint;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;

// import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.IActionListener;
import com.mendhak.gpslogger.common.Utilities;
import com.mendhak.gpslogger.loggers.utils.LocationBuffer;

import java.io.IOException;

public abstract class AbstractLiveLogger extends AbstractLogger {
    protected final LocationBuffer loc_buffer = new LocationBuffer();
    protected final static long maxWaitUpload = 10000; // Upload will be failed if longs more than maxWaitUpload millisecsec
    protected long timeStartUpload;
    protected final static int sleepTimeUpload = 100;  // Time to sleep for upload thread waiting for upload (one cycle)
    protected int minbufsize=0; // Should be set by child class if needed
    protected int maxbufsize=32;
    protected int MAX_TRY=3;
    protected int MAX_SEND_FAILED=5;

    private Runnable flusher;
// **** Handler removed by Peter 01/11/2014 - replaced by execAsyncFlush() call in Write method
//    private Handler handler;
    private FlusherAsyncTask flushertask;
    private boolean loggerIsClosing = false;

    private static String name = "AbstractLiveLogger";
    public abstract boolean liveUpload(LocationBuffer.BufferedLocation bloc) throws IOException;

    /**
     * Called after buffer has been flushed when closing.
     */
    public void closeAfterFlush(){
        // default is empty.
    }

    public AbstractLiveLogger(final int minsec, final int mindist){
        super(minsec, mindist);
        maxbufsize= AppSettings.getALMaxBufSize();

//        this.handler = new Handler();

        flusher = new Runnable() {
            @Override
            public void run() {
                execAsyncFlush();
//                handler.postDelayed(flusher, minsec * 1000);
            }
        };
        flusher.run();
    }

    private class FlusherAsyncTask extends AsyncTask<LocationBuffer, Void, Void> {
        @Override
        protected Void doInBackground(LocationBuffer... buffers) {
            for (LocationBuffer buf : buffers){
                LocationBuffer.BufferedLocation b;
                int bufsize=buf.size();
                Utilities.LogDebug(name + " flushing buffer (" + bufsize + ")");
                int i=0;
                int sent=0, failed=0;
                int maxtry=MAX_TRY;
                // TODO: ignore skipping by minbufsize using timeout (got from user settings)
                if ( (bufsize < minbufsize) && (!loggerIsClosing) ) {
                    Utilities.LogDebug(name + " flushing aborted: minbufsize=" + minbufsize);
                    return null;
                }
                do {
                    sent=0;
                    failed=0;
                    for (i = 0; i < bufsize; i++) {
                        try {
                            b = buf.peek();
                            if (b == null) break;
                            Utilities.LogDebug(name + " flushing elt " + i);
                            Utilities.LogDebug("flushing location time: " + b.timems);

                            if (liveUpload(b)) {
                                buf.pop();
                                sent++;
                            } else {
                                Utilities.LogDebug(name + " failed flush elt " + i);
                                failed++;
                            }
                        } catch (IOException ex) {
                            Utilities.LogDebug(name + ": sending fix", ex);
                            failed++;
                        }
                        if(failed>MAX_SEND_FAILED) break;
                    }
                    Utilities.LogDebug(name + ": finished flushing " + i + " locations");
                    maxtry--;
                    bufsize=buf.size();
                } while( (maxtry>0) && (sent>0) && (bufsize>0) );  // Trying to flush new locations if liveUpload was successful
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void e){
            if (loggerIsClosing) {
                closeAfterFlush();
            }
        }
    };

//    int currentapiVersion = android.os.Build.VERSION.SDK_INT;

    @SuppressLint("NewApi")
    private void execAsyncFlush(){
        if (flushertask == null || flushertask.getStatus() != AsyncTask.Status.RUNNING){
            Utilities.LogDebug(name + " starting flusher task");

            if (flushertask == null || flushertask.getStatus() == AsyncTask.Status.FINISHED) {
                flushertask = new FlusherAsyncTask();
            }
//            if (currentapiVersion >= android.os.Build.VERSION_CODES.HONEYCOMB) {
//                flushertask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//            } else {
                flushertask.execute(loc_buffer);
//            }
        } else {
            Utilities.LogDebug(name + " flusher task already running");
        }
    }

    @Override
    public void close() throws Exception{
//        this.handler.removeCallbacks(flusher);
        loggerIsClosing = true;
        execAsyncFlush();
    }

    @Override
    public void Write(Location loc) throws Exception
    {
//        final long now = SystemClock.elapsedRealtime();
//        calendar.setTimeInMillis(loc.getTime());
        int bufsize=loc_buffer.size();
        if (bufsize > maxbufsize) {
            Utilities.LogDebug(name  + " cannot push: bufsize= " + bufsize + " > "+maxbufsize);
            Utilities.LogDebug(name  + " deleting the oldest point");
            loc_buffer.pop();
        }
        SetLatestTimeStamp(System.currentTimeMillis());
        // get time from system, not location: prevent emulator problem with wrong date
        // in simulated location
        final long ms_of_day =  System.currentTimeMillis(); //loc.getTime();
//        final int second_of_day =
//                calendar.get(Calendar.HOUR_OF_DAY) * 3600
//                        + calendar.get(Calendar.MINUTE) * 60
//                        + calendar.get(Calendar.SECOND);
//        final int ms_of_day = second_of_day * 1000
//                + calendar.get(Calendar.MILLISECOND);
        loc_buffer.push(
                ms_of_day,
                loc.getLatitude(), loc.getLongitude(),
                (int)loc.getAltitude(),
                (int)loc.getBearing(),
                loc.getSpeed()
        );
        Utilities.LogDebug(name  + " pushed (" + loc_buffer.size() + ")");

//
//        if (now >= nextUpdateTime) {
//            new WriteAsync().execute(loc);
//            nextUpdateTime = now + intervalMS;
//        }
// *** Added by Peter 01/11/2014 ***
        execAsyncFlush();
    }

    protected void startUploadTimer() {
        timeStartUpload=System.currentTimeMillis();
    }

    protected boolean isTimedOutUpload() {
        long timeCurrent=System.currentTimeMillis();
        if( (timeCurrent-timeStartUpload)> maxWaitUpload ) return true;
            else return false;
    }
}
