package com.ustc.wsn.mobileData.Listenter;
/**
 * Created by halo on 2017/7/1.
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Criteria;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.util.Log;

import com.ustc.wsn.mobileData.bean.Geomagnetism;
import com.ustc.wsn.mobileData.bean.LocationData;
import com.ustc.wsn.mobileData.bean.math.myMath;

import java.util.Iterator;

public class DetectorLocationListener {
    private LocationManager lm;
    private int DataSize = 10000;// GPS缓冲池大小为10000
    private final String TAG = DetectorLocationListener.class.toString();
    private String[] slocation;
    private static String bear;
    private static String Velocity;
    private int loc_cur;
    private int loc_old;
    private static int bear_count=0;
    private static int last_bear_count=0;
    private static int bear_same_count=0;

    private static int Velocity_count=0;
    private static int last_Velocity_count=0;
    private static int Velocity_same_count=0;
    // private StoreData sd;

    /**
     * 初始化
     *
     * @param ctx
     */
    @SuppressLint("MissingPermission")
    public DetectorLocationListener(Context ctx) {
        // 判断GPS是否正常启动
        slocation = new String[DataSize];
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        // sd = new StoreData();

        // 为获取地理位置信息时设置查询条件
        String bestProvider = lm.getBestProvider(getCriteria(), true);
        // 获取位置信息
        // 如果不设置查询要求，getLastKnownLocation方法传人的参数为LocationManager.GPS_PROVIDER
        Location location = lm.getLastKnownLocation(bestProvider);
        // updateView(location);
        // store(location);
        // if(location != null)
        loc_cur = 0;
        loc_old = 0;
        if (location != null) {
            slocation[loc_cur] = (new LocationData(location.getLongitude(), location.getLatitude(),
                    System.currentTimeMillis(), location.getSpeed(), location.getBearing())).toString();
            //bear = String.valueOf(location.getBearing());
            loc_cur = (loc_cur + 1) % DataSize;

            myMath.updateGravity(location.getLatitude(),location.getAltitude());
            Geomagnetism gm = new Geomagnetism(location.getLongitude(), location.getLatitude());
            myMath.updateDeclination((float) gm.getDeclination());
            myMath.updateGeographicalParams();
            Log.d(TAG," magnetic declination\t"+gm.getDeclination());
        }
        // store(location);
        // 监听状态
        lm.addGpsStatusListener(listener);
        // 绑定监听，有4个参数
        // 参数1，设备：有GPS_PROVIDER和NETWORK_PROVIDER两种
        // 参数2，位置信息更新周期，单位毫秒
        // 参数3，位置变化最小距离：当位置距离变化超过此值时，将更新位置信息
        // 参数4，监听
        // 备注：参数2和3，如果参数3不为0，则以参数3为准；参数3为0，则通过时间来定时更新；两者为0，则随时刷新

        // 1秒更新一次，或最小位移变化超过1米更新一次；
        // 注意：此处更新准确度非常低，推荐在service里面启动一个Thread，在run中sleep(10000);然后执行handler.sendMessage(),更新位置
        lm.requestLocationUpdates(bestProvider, 5000, 5, locationListener);
    }


    public String getLocation() {
        if (loc_old != loc_cur)// 非空
        {
            int i = loc_old;
            loc_old = (loc_old + 1) % DataSize;
            return slocation[i];
        } else
            return null;
    }

    public static String getCurrentBear() {
        if (bear != null&&bear_same_count<2)// 非空且bear相同时间不超过两个窗口
        {
            if(bear_count==last_bear_count)//相等则bear没更新
            {
                bear_same_count++;
            }
            last_bear_count=bear_count;
            return bear;
        }
        else{//bear不能用
            bear_same_count=0;
            bear = null;
            return bear;
        }

    }

    public static String getCurrentVelocity() {
        if (Velocity != null&&Velocity_same_count<2)// 非空且Velocity相同时间不超过两个窗口
        {
            if(Velocity_count==last_Velocity_count)//相等则Velocity没更新
            {
                Velocity_same_count++;
            }
            last_Velocity_count=Velocity_count;
            return Velocity;
        }
        else{//Velocity不能用
            Velocity_same_count=0;
            Velocity = null;
            return Velocity;
        }

    }

    public void closeLocation() {
        if (lm != null) {
            if (locationListener != null) {
                lm.removeUpdates(locationListener);
                locationListener = null;
            }
            if(listener !=null) {
                lm.removeGpsStatusListener(listener);
            }
            lm = null;
        }
    }

    // 位置监听
    private LocationListener locationListener = new LocationListener() {

        /**
         * 位置信息变化时触发
         */
        public void onLocationChanged(Location location) {
            // store(location);
            if (location != null && (loc_cur + 1) % DataSize != loc_old)// 未满
            {
                slocation[loc_cur] = (new LocationData(location.getLongitude(), location.getLatitude(),
                        System.currentTimeMillis(), location.getSpeed(), location.getBearing())).toString();
                bear = String.valueOf(location.getBearing());
                bear_count++;
                Velocity = String.valueOf(location.getSpeed());
                Velocity_count++;
                loc_cur = (loc_cur + 1) % DataSize;

                myMath.updateGravity(location.getLatitude(),location.getAltitude());
                Geomagnetism gm = new Geomagnetism(location.getLongitude(), location.getLatitude());
                myMath.updateDeclination((float) gm.getDeclination());
                myMath.updateGeographicalParams();
                Log.d(TAG," magnetic declination\t"+gm.getDeclination());
            }
			/*
			 * slocation = location; if (thread == null) { thread = new
			 * Thread(new Runnable() {
			 *
			 * @Override public void run() { while (true) { //sLocation =
			 * getBestLocation(); if (slocation != null) { store(slocation); }
			 * synchronized (this) { try { wait(1000); } catch
			 * (InterruptedException e) { e.printStackTrace(); } } } } });
			 * thread.start(); }
			 */
            // updateView(location);
            // Log.i(TAG, "时间：" + location.getTime());
            // Log.i(TAG, "经度：" + location.getLongitude());
            // Log.i(TAG, "纬度：" + location.getLatitude());
            // Log.i(TAG, "海拔：" + location.getAltitude());
        }

        /**
         * GPS状态变化时触发
         */
        public void onStatusChanged(String provider, int status, Bundle extras) {
            switch (status) {
                // GPS状态为可见时
                case LocationProvider.AVAILABLE:
                    // Log.i(TAG, "当前GPS状态为可见状态");
                    break;
                // GPS状态为服务区外时
                case LocationProvider.OUT_OF_SERVICE:
                    // Log.i(TAG, "当前GPS状态为服务区外状态");
                    break;
                // GPS状态为暂停服务时
                case LocationProvider.TEMPORARILY_UNAVAILABLE:
                    // Log.i(TAG, "当前GPS状态为暂停服务状态");
                    break;
            }
        }

        /**
         * GPS开启时触发
         */
        public void onProviderEnabled(String provider) {
            Location location = lm.getLastKnownLocation(provider);
            // updateView(location);
            // store(location);
            if (location != null) {
                slocation[loc_cur] = (new LocationData(location.getLongitude(), location.getLatitude(),
                        System.currentTimeMillis(), location.getSpeed(), location.getBearing())).toString();
                //bear = String.valueOf(location.getBearing());
                loc_cur = (loc_cur + 1) % DataSize;

                myMath.updateGravity(location.getLatitude(),location.getAltitude());
                Geomagnetism gm = new Geomagnetism(location.getLongitude(), location.getLatitude());
                myMath.updateDeclination((float) gm.getDeclination());
                myMath.updateGeographicalParams();
                Log.d(TAG," magnetic declination\t"+gm.getDeclination());
            }
        }

        /**
         * GPS禁用时触发
         */
        public void onProviderDisabled(String provider) {
            // updateView(null);
            // store(null);
            slocation = null;
        }

    };

    // 状态监听
    GpsStatus.Listener listener = new GpsStatus.Listener() {
        public void onGpsStatusChanged(int event) {
            switch (event) {
                // 第一次定位
                case GpsStatus.GPS_EVENT_FIRST_FIX:
                    // Log.i(TAG, "第一次定位");
                    break;
                // 卫星状态改变
                case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                    // Log.i(TAG, "卫星状态改变");
                    // 获取当前状态
                    if(lm!=null) {
                        GpsStatus gpsStatus = lm.getGpsStatus(null);
                        // 获取卫星颗数的默认最大值
                        int maxSatellites = gpsStatus.getMaxSatellites();
                        // 创建一个迭代器保存所有卫星
                        Iterator<GpsSatellite> iters = gpsStatus.getSatellites().iterator();
                        int count = 0;
                        while (iters.hasNext() && count <= maxSatellites) {
                            GpsSatellite s = iters.next();
                            count++;
                        }
                        // System.out.println("搜索到：" + count + "颗卫星");
                    }
                    break;
                // 定位启动
                case GpsStatus.GPS_EVENT_STARTED:
                    // Log.i(TAG, "定位启动");
                    break;
                // 定位结束
                case GpsStatus.GPS_EVENT_STOPPED:
                    // Log.i(TAG, "定位结束");
                    break;
            }
        };
    };

    /**
     * 返回查询条件
     *
     * @return
     */
    private Criteria getCriteria() {
        Criteria criteria = new Criteria();
        // 设置定位精确度 Criteria.ACCURACY_COARSE比较粗略，Criteria.ACCURACY_FINE则比较精细
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        // 设置是否要求速度
        criteria.setSpeedRequired(true);
        // 设置是否允许运营商收费
        criteria.setCostAllowed(false);
        // 设置是否需要方位信息
        criteria.setBearingRequired(true);
        // 设置是否需要海拔信息
        criteria.setAltitudeRequired(true);
        // 设置对电源的需求
        criteria.setPowerRequirement(Criteria.POWER_LOW);
        return criteria;
    }
}
