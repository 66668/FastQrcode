package com.ruijia.qrcode.service;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.ruijia.qrcode.Constants;
import com.ruijia.qrcode.QrAIDLInterface;
import com.ruijia.qrcode.QrApplication;
import com.ruijia.qrcode.QrProgressCallback;
import com.ruijia.qrcode.listener.OnServiceAndActListener;
import com.ruijia.qrcode.utils.CacheUtils;
import com.ruijia.qrcode.utils.CheckUtils;
import com.ruijia.qrcode.utils.CodeUtils;
import com.ruijia.qrcode.utils.ConvertUtils;
import com.ruijia.qrcode.utils.IOUtils;
import com.ruijia.qrcode.utils.SPUtil;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * aidl服务端service,给aidl客户端提供service接口
 */
public class QRXmitService extends Service {
    public static final String TAG = "SJY";
    //---------------------------变量-------------------------------
    private Handler handler;

    //---------------------------变量-------------------------------
    private AtomicBoolean isServiceDestory = new AtomicBoolean(false);
    //RemoteCallbackList是专门用于删除跨进程listener的接口，它是一个泛型，支持管理多个回调。
    private RemoteCallbackList<QrProgressCallback> mListener = new RemoteCallbackList<>();
    private String selectPath;//当前传输的文件
    private OnServiceAndActListener listener;//
    private List<String> newDatas = new ArrayList<>();
    private List<Bitmap> maps = new ArrayList<>();

    /**
     * 客户端开启连接后，自动执行
     */
    public QRXmitService() {
        handler = new Handler();
        //设置默认发送时间间隔
        SPUtil.putInt(Constants.TIME_INTERVAL, 150);
        //默认文件大小
        SPUtil.putInt(Constants.FILE_SIZE, 5);
    }

    @Override
    public IBinder onBind(Intent intent) {
        //通过ServiceConnection在activity中拿到Binder对象
        return new QrAIDLServiceBinder();
    }


    //==========================================================================================================================
    //=================================以下为app 进程间的交互，包括客户端调用服务端，服务端回调客户端===================================
    //==========================================================================================================================


    //---------------------------------------------AIDL接口实现--------------------------------------------

    /**
     * 接口方法，由service实现
     */
    public class QrAIDLServiceBinder extends QrAIDLInterface.Stub {

        //act与service交互使用
        public QRXmitService geSerVice() {
            return QRXmitService.this;
        }

        //aidl使用
        @Override
        public void QRSend(String localPath) throws RemoteException {
            srvQrSend(localPath);
        }

        @Override
        public String QRRecv() throws RemoteException {
            return srvQRRecv();
        }

        @Override
        public boolean QrCtrl(int timeInterval, int StrLen) throws RemoteException {
            return srvQRCtrl(timeInterval, StrLen);
        }

        @Override
        public void register(QrProgressCallback listener) throws RemoteException {
            //绑定
            mListener.register(listener);
        }

        @Override
        public void unregister(QrProgressCallback listener) throws RemoteException {
            //解除
            mListener.unregister(listener);
        }


    }


    @Override
    public void onDestroy() {
        isServiceDestory.set(true);
        super.onDestroy();
    }


    //-----------------------《客户端-->服务端》操作（不同进程）----------------------

    /**
     *
     */
    public boolean srvQRCtrl(int timeInterval, int fileSize) {
        Log.d(TAG, "服务端设置参数-QRCtrl--timeInterval=" + timeInterval + "--fileSize=" + fileSize);
        SPUtil.putInt(Constants.TIME_INTERVAL, timeInterval);
        SPUtil.putInt(Constants.FILE_SIZE, fileSize);
        return (SPUtil.getInt(Constants.TIME_INTERVAL, 0) != 0) && (SPUtil.getInt(Constants.TIME_INTERVAL, 0) != 0);
    }

    /**
     * 核心方法
     * <p>
     * (1)验证文件是否可以传送
     * <p>
     * (2)文件分解成字符流，在分解成 指定长度的
     */
    public void srvQrSend(String localPath) {
        Log.d("SJY", "QRXmitService--QrSend-localPath=" + localPath);
        //判断文件是否存在
        File file = new File(localPath);
        if (file == null || !file.exists()) {
            isTrans(false, "文件不存在");
        } else {
            selectPath = file.getAbsolutePath();
            split2IO(file);
        }
    }


    /**
     *
     */
    public String srvQRRecv() {
        return "请参考回调";
    }


    //-----------------------文件拆分操作，耗时操作----------------------

    /**
     * (1)文件分解成字符流
     *
     * @param file
     */
    private void split2IO(final File file) {
        final int maxSize = SPUtil.getInt(Constants.FILE_SIZE, 0);
        if (maxSize == 0) {
            Log.e("SJY", "service无法使用SharedPreferences");
            return;
        }
        new AsyncTask<Void, Void, String>() {

            @Override
            protected String doInBackground(Void... voids) {
                long startTime = System.currentTimeMillis();
                //File转String
                String data = IOUtils.fileToBase64(file);
                long len = data.length();
                long time = System.currentTimeMillis() - startTime;

                //文件长度是否超出最大传输
                boolean isTrans = false;
                if ((len / 1024 / 1024) > maxSize) {
                    isTrans = false;
                } else {
                    isTrans = true;
                }

                //回调客户端
                final int N = mListener.beginBroadcast();
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.splitToIoTime(time, "splitToIoTime");
                        callback.isTrans(isTrans, "splitToIoTime--文件长度=" + len + "B");
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                return data;

            }

            @Override
            protected void onPostExecute(String data) {
                super.onPostExecute(data);
                //拿到文件的字符流
                createArray(data);
            }
        }.execute();

    }

    /**
     * (2)字符流-->List<String>
     */
    private void createArray(final String data) {
        new AsyncTask<Void, Void, List<String>>() {

            @Override
            protected List<String> doInBackground(Void... voids) {
                long startTime = System.currentTimeMillis();
                //String切割成list
                List<String> orgDatas = IOUtils.stringToArray(data);
                if (orgDatas == null) {
                    return null;
                }
                long time = System.currentTimeMillis() - startTime;
                //
                splitToArrayTime(time, "字符流-->原始List<String>");

                return orgDatas;
            }

            @Override
            protected void onPostExecute(List<String> list) {
                super.onPostExecute(list);
                //拿到原始list,转成bitmap
                if (list == null || list.size() <= 0) {
                    //回调客户端
                    isTrans(false, "createArray--原始数据长度超过指定长度！");
                    return;
                } else {
                    createNewArray(list);
                }
            }
        }.execute();

    }

    /**
     * (3) 原始List转有标记的List数据
     * <p>
     * 说明：String数据段头标记：snd1234512345,长度13;尾标记：RJQR,长度4
     * <p>
     * 头标记：
     * <p>
     * snd：长度3：表示发送 长度3
     * <p>
     * 12345:长度5：表示list总长度
     * <p>
     * 12345：长度5：表示第几个数据片段
     * <p>
     * 尾标记：长度4，表示这段数据是否解析正确 RJQR
     *
     * @param orgDatas
     */
    private void createNewArray(final List<String> orgDatas) {
        new AsyncTask<Void, Void, List<String>>() {

            @Override
            protected List<String> doInBackground(Void... voids) {
                List<String> sendDatas = new ArrayList<>();
                long startTime = System.currentTimeMillis();
                try {
                    //添加标记，
                    // 前5位是size标记，后5位是第几个标记
                    int size = orgDatas.size();
                    String strSize = ConvertUtils.int2String(size);//不会处理大于10000的size
                    for (int i = 0; i < size; i++) {
                        String pos = ConvertUtils.int2String(i);
                        //拼接数据-->格式：snd(发送标记)+00022(数据长度)+00001(第几个，从0开始)+数据段
                        sendDatas.add("snd" + strSize + pos + orgDatas.get(i) + "RJQR"); //eg 00120001xxstr
                    }
                } catch (Exception e) {
                    isTrans(false, e.toString());
                    e.printStackTrace();
                    return null;
                }

                //回调客户端
                long time = System.currentTimeMillis() - startTime;
                createNewArray(time, "null");
                return sendDatas;
            }

            @Override
            protected void onPostExecute(List<String> list) {
                super.onPostExecute(list);
                //拿到有标记的List,再转qr bitmap
                if (list == null || list.size() <= 0) {
                    //已处理
                    return;
                } else {
                    /**
                     * 方式1：直接使用新数据，识别的时候一张张转qrbitmap
                     * 方式2：集中转qrbitmap,识别的间隔速度可以短一些。
                     *
                     * 本次用方式2测试。
                     */
                    newDatas = list;
                    //新数据转qrbitmap
                    createQrBitmap(list);
                }

            }
        }.execute();
    }

    /**
     * (4)list转qrbitmap
     *
     * <p>
     */
    private void createQrBitmap(final List<String> sendDatas) {
        new AsyncTask<Void, Void, List<Bitmap>>() {

            @Override
            protected List<Bitmap> doInBackground(Void... voids) {
                //保存新数据
                CacheUtils.getInstance().put(Constants.KEY_STRLIST, (Serializable) sendDatas);
                //
                List<Bitmap> sendImgs = new ArrayList<>();
                long startTime = System.currentTimeMillis();

                //sendDatas 转qrbitmap
                int size = sendDatas.size();
                for (int i = 0; i < size; i++) {
                    long start = System.currentTimeMillis();
                    Bitmap bitmap = CodeUtils.createByMultiFormatWriter(sendDatas.get(i), 400);
                    sendImgs.add(bitmap);
                    //回调客户端
                    long end = System.currentTimeMillis() - start;
                    createQrImgProgress(size, i, "生成单张二维码耗时=" + end);

                }
                //回调客户端
                long time = System.currentTimeMillis() - startTime;
                createQrImgTime(time, "createQrBitmap:list--->qrbitmap");
                //保存
                CacheUtils.getInstance().put(Constants.KEY_BITMAPLIST, (Serializable) sendImgs);
                return sendImgs;
            }

            @Override
            protected void onPostExecute(List<Bitmap> bitmapList) {
                super.onPostExecute(bitmapList);
                maps = bitmapList;
                //service与act的交互
                //调起链路层传输数据
                serviceStartAct();
            }
        }.execute();

    }

    //-----------------------《服务端-->客户端》回调（不同进程）----------------------

    /**
     * 回调客户端
     * <p>
     * 文件是否可以传输
     * <p>
     * 请安这个步骤操作
     */
    public void isTrans(final boolean isSuccess, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();//成对出现
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.isTrans(isSuccess, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 回调客户端
     * <p>
     * (1)文件转成字符流耗时。
     * <p>
     * 请安这个步骤操作
     *
     * @param time
     * @param msg
     */
    public void splitToIoTime(final long time, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.splitToIoTime(time, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 回调客户端
     * <p>
     * (2)字符流生成array
     * <p>
     * 请安这个步骤操作
     *
     * @param time
     * @param msg
     */
    public void splitToArrayTime(final long time, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.splitToArrayTime(time, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }


    /**
     * 回调客户端
     * <p>
     * (3)orglist转带标记的List
     * <p>
     * 请安这个步骤操作
     *
     * @param time
     * @param msg
     */
    public void createNewArray(final long time, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.createNewArrayTime(time, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 回调客户端
     * <p>
     * （4）合成二维码图的耗时
     * <p>
     * 请安这个步骤操作
     *
     * @param time
     * @param msg
     */
    public void createQrImgTime(final long time, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.createQrImgTime(time, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 回调客户端
     * <p>
     * 进度
     * <p>
     * 请安这个步骤操作
     *
     * @param total
     * @param msg
     */
    public void createQrImgProgress(final int total, final int position, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.createQrImgProgress(total, position, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 回调客户端
     * <p>
     * 传输进度
     * <p>
     * 请安这个步骤操作
     *
     * @param total
     * @param msg
     */
    public void qrTransProgress(final long time, final int total, final int position, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.transProgress(time, total, position, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }


    /**
     * 回调客户端
     * <p>
     * 二维码传输耗时统计
     * <p>
     * 请安这个步骤操作
     *
     * @param time
     * @param msg
     */
    public void transcomplete(final long time, final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener == null) {
                    return;
                }
                //检查注册的回调个数
                final int N = mListener.beginBroadcast();
                //遍历出要用的callback
                try {
                    for (int i = 0; i < N; i++) {
                        QrProgressCallback callback = mListener.getBroadcastItem(i);
                        //处理回调
                        callback.transTime(time, msg);
                        mListener.finishBroadcast();//成对出现2
                        // 解绑callback
//                        mListener.unregister(callback);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    //===========================================================================================================================
    //=================================以下为同一进程下，act与service的交互：包括service回调act,act回调service===================================
    //===========================================================================================================================

    //service与act通讯有两种方式1接口，2广播。本demo使用接口。

    /**
     * 由service调起act
     */

    private void serviceStartAct() {
        if (checkActAlive() && isActFrontShow()) {
            isTrans(true, "MainAct在运行");
            //接口回调
            if (listener != null) {
                listener.onQrsend(selectPath, newDatas, maps);
            } else {
                isTrans(false, "listener==null");
            }

        } else {
            isTrans(true, "MainAct不在前台，正在开启");
            startApp();
        }
    }

    private boolean checkActAlive() {
        return CheckUtils.isActivityAlive(QrApplication.getInstance(), "com.ruijia.qrcode", "MainAct");
    }

    private boolean isActFrontShow() {
        return CheckUtils.isActFrontShow(QrApplication.getInstance(), "com.ruijia.qrcode.MainAct");
    }

    /**
     * 由service调起app的act界面
     * 由于intent传值 不能传大数据，所以使用接口回调方式。
     */
    private void startApp() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                //启动应用，参数为需要自动启动的应用的包名
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.ruijia.qrcode");
                startActivity(launchIntent);
            }
        });
    }

    /**
     * act调用，回调给另一个app
     * 识别完成的回调
     */
    public void setAidlQrCodeComplete(long time, String result) {
        transcomplete(time, result);
    }

    /**
     * 识别出结果的回调
     */
    public void setAidlQrCodeSuccess(long successTime, int size, int pos, String msg) {
        qrTransProgress(successTime, size, pos, msg);
    }


    /**
     * 设置回调
     *
     * @param listener
     */
    public void setListener(OnServiceAndActListener listener) {
        this.listener = listener;
    }

    /**
     * act的service连接完成后，通知service回调act
     */
    public void startServiceTrans() {
        listener.onQrsend(selectPath, newDatas, maps);
    }
}
