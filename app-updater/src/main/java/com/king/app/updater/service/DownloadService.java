package com.king.app.updater.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.king.app.updater.R;
import com.king.app.updater.UpdateConfig;
import com.king.app.updater.callback.UpdateCallback;
import com.king.app.updater.constant.Constants;
import com.king.app.updater.http.HttpManager;
import com.king.app.updater.http.IHttpManager;
import com.king.app.updater.util.AppUtils;
import com.king.app.updater.util.NotificationUtils;

import java.io.File;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;


/**
 * @author Jenly <a href="mailto:jenly1314@gmail.com">Jenly</a>
 */
public class DownloadService extends Service {

    private DownloadBinder mDownloadBinder = new DownloadBinder();
    /**
     * 是否在下载，防止重复下载。
     */
    private boolean isDownloading;
    /**
     * 最后更新进度，用来降频刷新
     */
    private int mLastProgress = -1;
    /**
     * 最后进度更新时间，用来降频刷新
     */
    private long mLastTime;
    /**
     * 失败后重新下载次数
     */
    private int mCount = 0;

    private IHttpManager mHttpManager;

    private File mFile;

    private Context getContext(){
        return this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if(intent != null){

            boolean isStop = intent.getBooleanExtra(Constants.KEY_STOP_DOWNLOAD_SERVICE,false);
            if(isStop){
                stopDownload();
            } else if(!isDownloading){
                //是否实通过通知栏触发重复下载
                boolean isReDownload = intent.getBooleanExtra(Constants.KEY_RE_DOWNLOAD,false);
                if(isReDownload){
                    mCount++;
                }
                //获取配置信息
                UpdateConfig config =  intent.getParcelableExtra(Constants.KEY_UPDATE_CONFIG);
                startDownload(config,null,null);
            }else{
                Log.w(Constants.TAG,"Please do not duplicate downloads.");
            }
        }

        return super.onStartCommand(intent, flags, startId);

    }


    //----------------------------------------

    /**
     * 开始下载
     * @param config
     * @param httpManager
     * @param callback
     */
    public void startDownload(UpdateConfig config,IHttpManager httpManager,UpdateCallback callback){

        if(config == null){
            return;
        }

        if(callback != null){
            callback.onDownloading(isDownloading);
        }

        if(isDownloading){
            Log.w(Constants.TAG,"Please do not duplicate downloads.");
            return;
        }

        String url = config.getUrl();
        String path = config.getPath();
        String filename = config.getFilename();

        //如果保存路径为空则使用缓存路径
        if(TextUtils.isEmpty(path)){
            path = getExternalFilesDir(getContext());
        }
        File dirFile = new File(path);
        if(!dirFile.exists()){
            dirFile.mkdirs();
        }

        //如果文件名为空则使用路径
        if(TextUtils.isEmpty(filename)){
            filename = AppUtils.getAppFullName(getContext(),url,getResources().getString(R.string.app_name));
        }

        mFile = new File(path,filename);
        if(mFile.exists()){//文件是否存在

            Integer versionCode = config.getVersionCode();
            String apkMD5 = config.getApkMD5();
            //是否存在相同的apk
            boolean isExistApk = false;
            if(!TextUtils.isEmpty(apkMD5)){//如果存在MD5，则优先校验MD5
                Log.d(Constants.TAG,String.format("UpdateConfig.apkMD5:%s",apkMD5));
                isExistApk = AppUtils.checkFileMD5(mFile,apkMD5);
            }else if(versionCode != null){//如果存在versionCode，则校验versionCode
                try{
                    Log.d(Constants.TAG,String.format("UpdateConfig.versionCode:%d",versionCode));
                    isExistApk = AppUtils.apkExists(getContext(),versionCode,mFile);
                }catch (Exception e){
                    Log.w(Constants.TAG,e);
                }
            }

            if(isExistApk){
                //本地已经存在要下载的APK
                Log.d(Constants.TAG,"CacheFile:" + mFile);
                if(config.isInstallApk()){
                    String authority = config.getAuthority();
                    if(TextUtils.isEmpty(authority)){//如果为空则默认
                        authority = getContext().getPackageName() + Constants.DEFAULT_FILE_PROVIDER;
                    }
                    AppUtils.installApk(getContext(),mFile,authority);
                }
                if(callback!=null){
                    callback.onFinish(mFile);
                }
                stopService();
                return;
            }

            //删除旧文件
            mFile.delete();
        }
        Log.d(Constants.TAG,"File:" + mFile);

        mHttpManager = httpManager != null ? httpManager : HttpManager.getInstance();
        mHttpManager.download(url,path,filename,config.getRequestProperty(),new AppDownloadCallback(config,callback));

    }

    /**
     * 停止下载
     */
    public void stopDownload(){
        if(mHttpManager != null){
            mHttpManager.cancel();
        }
    }

    /**
     * 获取缓存路径
     * @param context
     * @return
     */
    private String getExternalFilesDir(Context context) {
        File[] files = ContextCompat.getExternalFilesDirs(context,Constants.DEFAULT_DIR);
        if(files!=null && files.length > 0){
            return files[0].getAbsolutePath();
        }
        return context.getExternalFilesDir(Constants.DEFAULT_DIR).getAbsolutePath();

    }

    /**
     * 停止服务
     */
    private void stopService(){
        mCount = 0;
        stopSelf();
    }


    //---------------------------------------- DownloadCallback

    public class AppDownloadCallback implements IHttpManager.DownloadCallback{

        public UpdateConfig config;

        private boolean isShowNotification;

        private int notifyId;

        private String channelId;

        private String channelName;

        private int notificationIcon;

        private boolean isInstallApk;

        private String authority;

        private boolean isShowPercentage;

        private boolean isReDownload;

        private boolean isDeleteCancelFile;

        private boolean isCancelDownload;

        private UpdateCallback callback;

        private int reDownloads;


        private AppDownloadCallback(UpdateConfig config,UpdateCallback callback){
            this.config = config;
            this.callback = callback;
            this.isShowNotification = config.isShowNotification();
            this.notifyId = config.getNotificationId();
            this.reDownloads = config.getReDownloads();

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                this.channelId = TextUtils.isEmpty(config.getChannelId()) ? Constants.DEFAULT_NOTIFICATION_CHANNEL_ID : config.getChannelId();
                this.channelName = TextUtils.isEmpty(config.getChannelName()) ? Constants.DEFAULT_NOTIFICATION_CHANNEL_NAME : config.getChannelName();
            }
            if(config.getNotificationIcon() <= 0){
                this.notificationIcon = AppUtils.getAppIcon(getContext());
            }else{
                this.notificationIcon = config.getNotificationIcon();
            }

            this.isInstallApk = config.isInstallApk();

            this.authority = config.getAuthority();
            if(TextUtils.isEmpty(config.getAuthority())){//如果为空则默认
                authority = getContext().getPackageName() + Constants.DEFAULT_FILE_PROVIDER;
            }

            this.isShowPercentage = config.isShowPercentage();
            this.isReDownload = config.isReDownload();
            this.isDeleteCancelFile = config.isDeleteCancelFile();
            this.isCancelDownload = config.isCancelDownload();

        }

        @Override
        public void onStart(String url) {
            Log.d(Constants.TAG,"onStart:" + url);
            isDownloading = true;
            mLastProgress = 0;
            if(isShowNotification){
                NotificationUtils.showStartNotification(getContext(),notifyId,channelId,channelName,notificationIcon,getString(R.string.app_updater_start_notification_title),getString(R.string.app_updater_start_notification_content),config.isVibrate(),config.isSound(),isCancelDownload);
            }

            if(callback != null){
                callback.onStart(url);
            }
        }

        @Override
        public void onProgress(long progress, long total) {

            boolean isChange = false;
            long curTime = System.currentTimeMillis();
            if(mLastTime + 200 < curTime) {//降低更新频率
                mLastTime = curTime;

                int currProgress = Math.round(progress * 1.0f / total * 100.0f);
                if(currProgress != mLastProgress){//百分比改变了才更新
                    isChange = true;
                    mLastProgress = currProgress;
                    String percentage = currProgress + "%";
                    if(isShowNotification) {
                        String content = getString(R.string.app_updater_progress_notification_content);
                        if (isShowPercentage) {
                            content += percentage;
                        }

                        NotificationUtils.showProgressNotification(getContext(),notifyId, channelId, notificationIcon, getString(R.string.app_updater_progress_notification_title), content, currProgress, 100,isCancelDownload);

                    }
                }
            }

            if(callback != null){
                callback.onProgress(progress,total,isChange);
            }
        }

        @Override
        public void onFinish(File file) {
            Log.d(Constants.TAG,"onFinish:" + file);
            isDownloading = false;
            NotificationUtils.showFinishNotification(getContext(),notifyId,channelId,notificationIcon,getString(R.string.app_updater_finish_notification_title),getString(R.string.app_updater_finish_notification_content),file,authority);
            if(isInstallApk){
                AppUtils.installApk(getContext(),file,authority);
            }
            if(callback != null){
                callback.onFinish(file);
            }
            stopService();
        }

        @Override
        public void onError(Exception e) {
            Log.w(Constants.TAG,"onError:"+ e.getMessage());
            isDownloading = false;
            if(isShowNotification){
                //支持下载失败时重新下载，当重新下载次数不超过限制时才被允许
                boolean isReDownload = this.isReDownload && mCount < reDownloads;
                String content = isReDownload ? getString(R.string.app_updater_error_notification_content_re_download) : getString(R.string.app_updater_error_notification_content);
                NotificationUtils.showErrorNotification(getContext(),notifyId,channelId,notificationIcon,getString(R.string.app_updater_error_notification_title),content,isReDownload,config);
            }

            if(callback != null){
                callback.onError(e);
            }
            if(!isReDownload){
                stopService();
            }

        }

        @Override
        public void onCancel() {
            Log.d(Constants.TAG,"onCancel");
            isDownloading = false;
            NotificationUtils.cancelNotification(getContext(),notifyId);
            if(callback != null){
                callback.onCancel();
            }
            if(isDeleteCancelFile && mFile!=null){
                mFile.delete();
            }
            stopService();
        }
    }

    @Override
    public void onDestroy() {
        isDownloading = false;
        mHttpManager = null;
        super.onDestroy();
    }

    //---------------------------------------- Binder

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mDownloadBinder;
    }

    /**
     * 提供绑定服务的方式下载
     */
    public class DownloadBinder extends Binder {

        public void start(UpdateConfig config){
            start(config,null);
        }

        public void start(UpdateConfig config,UpdateCallback callback){
            start(config,null,callback);
        }

        public void start(UpdateConfig config,IHttpManager httpManager,UpdateCallback callback){
            startDownload(config,httpManager,callback);
        }

    }


}