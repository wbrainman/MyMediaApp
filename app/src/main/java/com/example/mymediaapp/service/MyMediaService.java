package com.example.mymediaapp.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.IBinder;
import android.service.media.MediaBrowserService;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

public class MyMediaService extends MediaBrowserServiceCompat {

//    private static final String TAG =MyMediaService.class.getSimpleName();
    private static final String TAG = "miaowuwu";

    private MediaSessionCompat mSession;
    private PlayerAdapter mPlayBack;
    private MediaNotificationManager mMediaNotificationManager;
    private MediaSessionCallback mCallback;
    private boolean mServiceInStartedState;

    @Override
    public void onCreate() {
        super.onCreate();

        mSession = new MediaSessionCompat(this, "MusicService");
        mCallback = new MediaSessionCallback();
        mSession.setCallback(mCallback);
        mSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        setSessionToken(mSession.getSessionToken());
        mMediaNotificationManager = new MediaNotificationManager(this);
        mPlayBack = new MediaPlayerAdapter(this, new MediaPlayerListener());
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        mMediaNotificationManager.onDestroy();
        mPlayBack.stop();
        mSession.release();
        Log.d(TAG, "onDestroy: MediaPlayerAdapter stopped, and MediaSession released");
    }

    public MyMediaService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                 int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot(MusicLibrary.getRoot(), null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(MusicLibrary.getMediaItems());
    }

    public class MediaSessionCallback extends MediaSessionCompat.Callback {
        private final List<MediaSessionCompat.QueueItem> mPlaylist = new ArrayList<>();
        private int mQueueIndex = -1;
        private MediaMetadataCompat mPrepareMedia;

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            mPlaylist.add(new MediaSessionCompat.QueueItem(description, description.hashCode()));
            mQueueIndex = (mQueueIndex == -1) ? 0 : mQueueIndex;
            mSession.setQueue(mPlaylist);
        }

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            mPlaylist.remove(new MediaSessionCompat.QueueItem(description, description.hashCode()));
            mQueueIndex = (mPlaylist.isEmpty()) ? -1 : mQueueIndex;
            mSession.setQueue(mPlaylist);
        }

        @Override
        public void onPrepare() {
            if (mQueueIndex < 0 && mPlaylist.isEmpty()) {
                return;
            }

            final String mediaId = mPlaylist.get(mQueueIndex).getDescription().getMediaId();
            mPrepareMedia = MusicLibrary.getMetadata(MyMediaService.this, mediaId);
            mSession.setMetadata(mPrepareMedia);

            if (!mSession.isActive()) {
                mSession.setActive(true);
            }
        }

        @Override
        public void onPlay() {
            if (mPlaylist.isEmpty()) {
                return;
            }
            if (mPrepareMedia == null) {
                onPrepare();
            }
            mPlayBack.playFromMedia(mPrepareMedia);
            Log.d(TAG, "onPlayFromMediaId: MediaSession active");
        }

        @Override
        public void onPause() {
            mPlayBack.pause();
        }

        @Override
        public void onStop() {
            mPlayBack.stop();
            mSession.setActive(false);
        }

        @Override
        public void onSkipToNext() {
            mQueueIndex = (++mQueueIndex % mPlaylist.size());
            mPrepareMedia = null;
            onPlay();
        }

        @Override
        public void onSkipToPrevious() {
            mQueueIndex = mQueueIndex > 0 ? mQueueIndex - 1 : mPlaylist.size() - 1;
            mPrepareMedia = null;
            onPlay();
        }

        @Override
        public void onSeekTo(long pos) {
            mPlayBack.seekTo(pos);
        }
    }

    public class MediaPlayerListener extends PlaybackInfoListener {

        private final ServiceManager mServiceManager;

        MediaPlayerListener() {
            mServiceManager = new ServiceManager();
        }

        @Override
        public void onPlaybackStateChange(PlaybackStateCompat state) {
            // Report the state to the MediaSession.
            mSession.setPlaybackState(state);

            // Manage the started state of this service.
            switch (state.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
                    mServiceManager.moveServiceToStartedState(state);
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    mServiceManager.updateNotificationForPause(state);
                    break;
                case PlaybackStateCompat.STATE_STOPPED:
                    mServiceManager.moveServiceOutOfStartedState(state);
                    break;
            }
        }

        class ServiceManager {

            private void moveServiceToStartedState(PlaybackStateCompat state) {
                Notification notification =
                        mMediaNotificationManager.getNotification(
                                mPlayBack.getCurrentMedia(), state, getSessionToken());

                if (!mServiceInStartedState) {
                    ContextCompat.startForegroundService(
                            MyMediaService.this,
                            new Intent(MyMediaService.this, MyMediaService.class));
                    mServiceInStartedState = true;
                }

                startForeground(MediaNotificationManager.NOTIFICATION_ID, notification);
            }

            private void updateNotificationForPause(PlaybackStateCompat state) {
                stopForeground(false);
                Notification notification =
                        mMediaNotificationManager.getNotification(
                                mPlayBack.getCurrentMedia(), state, getSessionToken());
                mMediaNotificationManager.getNotificationManager()
                        .notify(MediaNotificationManager.NOTIFICATION_ID, notification);
            }

            private void moveServiceOutOfStartedState(PlaybackStateCompat state) {
                stopForeground(true);
                stopSelf();
                mServiceInStartedState = false;
            }
        }
    }
}