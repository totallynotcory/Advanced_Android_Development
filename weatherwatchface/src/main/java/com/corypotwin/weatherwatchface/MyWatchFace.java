/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.corypotwin.weatherwatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.CursorLoader;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    private static final String LOG_TAG = "Sunshine Watchface";
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }



        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;

        Time mTime;
        String highTemp, lowTemp;
        Integer weatherImage;

        private GoogleApiClient mGoogleApiClient;
        final String TEMPHIGH_KEY = "high_temperature";
        final String TEMPLOW_KEY = "low_temperature";
        final String IMAGE_KEY = "image_of_weather";

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        private int specW, specH;

        private View watchFaceLayout;
        private RelativeLayout watchFaceRelLayout;
        private TextView hoursMins, minTemp, maxTemp;
        private ImageView tinyWeatherImage;

        private final Point displaySize = new Point();
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // Initialize Watch Face

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mTime = new Time();

            //  Connect to the Google API client for Weather data.

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();

            //  Inflate the Watch Face XML

            LayoutInflater inflater =
                    (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            watchFaceLayout = inflater.inflate(R.layout.watch_face_round, null);

            //  Setup display measurements

            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(displaySize);

            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x,
                    View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y,
                    View.MeasureSpec.EXACTLY);

            //  Bind our Views

            watchFaceRelLayout = (RelativeLayout) watchFaceLayout.findViewById(R.id.watchFaceLayout);
            hoursMins = (TextView) watchFaceLayout.findViewById(R.id.hoursMinutes);
            minTemp = (TextView) watchFaceLayout.findViewById(R.id.minTemp);
            maxTemp = (TextView) watchFaceLayout.findViewById(R.id.maxTemp);
            tinyWeatherImage = (ImageView) watchFaceLayout.findViewById(R.id.tinyWeatherImage);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }


        //  Take care of the data updates
        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.d(LOG_TAG, "GoogleApiClient connected.");

        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e(LOG_TAG, "GoogleApiClient Connection Suspended");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged: OMG IT'S EVERYTHING WE DREAMED ABOUT " + dataEventBuffer.toString());
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    setupTemperature(item);
                }
            }
            dataEventBuffer.release();
        }



        public void setupTemperature(DataItem data){

            if ("/weather_for_watch".equals(data.getUri().getPath())) {
                DataMap dataMap = DataMapItem.fromDataItem(data).getDataMap();
                lowTemp = dataMap.getString(TEMPLOW_KEY);
                highTemp = dataMap.getString(TEMPHIGH_KEY);
                weatherImage = dataMap.getInt(IMAGE_KEY);
                Log.d(LOG_TAG, "setupTemperature: it's gotten " + lowTemp);
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.e(LOG_TAG, "GoogleApiClient Connection Failed");
        }

        private void releaseGoogleApiClient() {
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                mGoogleApiClient.disconnect();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {

                releaseGoogleApiClient();
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            // Set the time
            mTime.setToNow();

            //  Set the watch time

            String currentTime = String.format("%02d", mTime.hour)
                    + ":" + String.format("%02d", mTime.minute);
            hoursMins.setText(currentTime);

            if(lowTemp != null) {
                Log.d(LOG_TAG, "onDraw: this is the low temp: " + lowTemp);
                minTemp.setText(lowTemp);
            }
            if(highTemp != null){
                maxTemp.setText(highTemp);
            }
            if(weatherImage != null){
                int imageResource = com.corypotwin.common.Utility.getIconResourceForWeatherCondition(weatherImage);
                tinyWeatherImage.setImageResource(imageResource);
            }

            if(mAmbient){
                watchFaceRelLayout.setBackgroundColor(getResources().getColor(R.color.black));
            } else{
                watchFaceRelLayout.setBackgroundColor(getResources().getColor(R.color.primary));
            }

            watchFaceLayout.measure(specW, specH);
            watchFaceLayout.layout(0, 0, watchFaceLayout.getMeasuredWidth(),
                    watchFaceLayout.getMeasuredHeight());

            canvas.drawColor(Color.BLACK);
            watchFaceLayout.draw(canvas);

        }


        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

}
