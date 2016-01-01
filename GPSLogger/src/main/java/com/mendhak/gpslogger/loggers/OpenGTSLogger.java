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

package com.mendhak.gpslogger.loggers;

import android.location.Location;
import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.IActionListener;
import com.mendhak.gpslogger.common.OpenGTSClient;
import com.mendhak.gpslogger.common.Utilities;
import com.mendhak.gpslogger.loggers.utils.LocationBuffer;

import java.io.IOException;


/**
 * Send locations directly to an OpenGTS server
 *
 * @author Francisco Reynoso
 */
public class OpenGTSLogger extends AbstractLiveLogger
{

    public static final String name = "OpenGTS";

    private boolean uploadFinished;
    private boolean uploadOK;

    public IActionListener al = new IActionListener()
            {
                @Override
                public void OnComplete()
                {
                    Utilities.LogDebug("OpenGTSLogger.LiveUpload: location uploaded successfully");
                    uploadFinished=true;
                    uploadOK=true;
                }

                @Override
                public void OnFailure()
                {
                    Utilities.LogDebug("OpenGTSLogger.LiveUpload: failed to upload location");
                    uploadFinished=true;
                    uploadOK=false;
                }
            };

    public OpenGTSLogger(int minsec, int mindist)
    {
        super(minsec,mindist);
        Utilities.LogDebug("OpenGTS constructor");
        this.minbufsize= AppSettings.getALMinBufSize();
//      TODO: better handling of minbufsize and MAX_BUFSIZE is needed - take the both from settings, put some filters on settings
        if(this.minbufsize > this.MAX_BUFSIZE/2) {
            this.minbufsize = this.MAX_BUFSIZE / 2;
            Utilities.LogDebug("minbufsize set too high, modified value is: " + this.minbufsize);
        }
    }

    @Override
    public boolean liveUpload(LocationBuffer.BufferedLocation bloc) throws IOException {
        String server = AppSettings.getOpenGTSServer();
        int port = Integer.parseInt(AppSettings.getOpenGTSServerPort());
        String path = AppSettings.getOpenGTSServerPath();
        String deviceId = AppSettings.getOpenGTSDeviceId();
        OpenGTSClient openGTSClient = new OpenGTSClient(server, port, path, al, null);
        uploadFinished=false;
        uploadOK=false;
        Utilities.LogDebug("OpenGTS liveUpload is trying to send location");
        startUploadTimer();
        openGTSClient.sendHTTP(deviceId, bloc.toLocation() );
        while ( (!uploadFinished) && (!isTimedOutUpload()) ) {
            try {
                Thread.sleep(sleepTimeUpload);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if(!uploadFinished) {   // Timed out
            Utilities.LogDebug("Cancelling OpenGTS liveUpload by timeout");
            openGTSClient.OnFailure();
        }
        Utilities.LogDebug("OpenGTS liveUpload finished sending location");
        return uploadOK;
    }

    @Override
    public void Annotate(String description, Location loc) throws Exception
    {
        // TODO Auto-generated method stub

    }

    @Override
    public String getName()
    {
        return name;
    }

}
