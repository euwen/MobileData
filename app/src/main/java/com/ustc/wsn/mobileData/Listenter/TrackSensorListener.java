package com.ustc.wsn.mobileData.Listenter;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import com.ustc.wsn.mobileData.bean.PathCal;
import com.ustc.wsn.mobileData.bean.Filter.EKF;
import com.ustc.wsn.mobileData.bean.Filter.FCF;
import com.ustc.wsn.mobileData.bean.Filter.GDF;
import com.ustc.wsn.mobileData.bean.Filter.GyroAtt;
import com.ustc.wsn.mobileData.bean.Filter.LPF_I;
import com.ustc.wsn.mobileData.bean.Filter.MeanFilter;
import com.ustc.wsn.mobileData.bean.Filter.ekfParams;
import com.ustc.wsn.mobileData.bean.Filter.ekfParamsHandle;
import com.ustc.wsn.mobileData.bean.Log.myLog;
import com.ustc.wsn.mobileData.bean.PathBasicData;
import com.ustc.wsn.mobileData.bean.PhoneState;
import com.ustc.wsn.mobileData.bean.math.FFT.*;
import com.ustc.wsn.mobileData.bean.math.LinearFit;
import com.ustc.wsn.mobileData.bean.math.myMath;

import java.util.ArrayList;

/**
 * Created by halo on 2018/1/28.
 */

public class TrackSensorListener implements SensorEventListener {
    private final String TAG = TrackSensorListener.this.toString();

    //数据窗口参数
    public final int windowSize = 25;//20*windowSize ms - 500ms
    public final int DurationWindow = 10;//
    public final int sampleInterval = 20;//ms
    public volatile int sampleIntervalReal = sampleInterval;//ms

    public int FFT_SampleInterval = 256 * sampleInterval;
    public int STFT_SECTIONS = 1;
    private int FFT_SIZE = 256;

    private float onVehicleProbability = 0.0f;

    private float phoneUseonVehicleProbability = 0.0f;

    private boolean onVehicle = false;
    private boolean phoneUseOnVehicle = false;

    //姿态滤波器选择
    private int AttitudeMode = PhoneState.Attitude_EKF;

    //状态参数
    private float GainStart = 0.1f;//Path首尾窗口内部，滑动窗口状态判断，附加增强比例Gain，比普通窗口要求更为严格
    private float GainStop = 0.1f;//Path首尾窗口内部，滑动窗口状态判断，附加增强比例Gain，比普通窗口要求更为严格
    private int Window_STATE = PhoneState.ABSOLUTE_STATIC_STATE;
    private volatile int GLOBAL_NOW_STATE = PhoneState.ABSOLUTE_STATIC_STATE;

    private volatile float[] stateValues = {0, 0};

    private float ACC_MEAN_ABSOLUTE_STATIC_THRESHOLD;
    private float ACC_MEAN_STATIC_THRESHOLD;
    private float ACC_VAR_ABSOLUTE_STATIC_THRESHOLD;
    private float ACC_VAR_STATIC_THRESHOLD;

    private float AMPDB_THRESHOLD;
    private float PEAK_FRE_THRESHOLD;
    public float VEHICLE_PROBABILITY_THRESHOLD;

    //路径参数
    public boolean ifNewPath = false;
    public boolean ifInterpolation = false;

    private StringBuffer positionBuffer;
    private StringBuffer InterpositionBuffer;
    private float[][] InterpositionQueue = null;
    private float[][] InterPosition = null;

    private volatile float[][] gyroQueue = new float[DurationWindow * windowSize][3];//
    private volatile float[][] magQueue = new float[DurationWindow * windowSize][3];//
    private volatile float[][] accQueue = new float[DurationWindow * windowSize][3];//
    private volatile float[] accNormQueue = new float[FFT_SIZE];//
    private volatile float[][] naccQueue = new float[DurationWindow * windowSize][3];
    private volatile float[][] positionQueue = new float[DurationWindow * windowSize][3];//位置队列
    private volatile float[] AccDeltTQueue = new float[DurationWindow * windowSize];//积分步长
    private volatile float[] GyroDeltTQueue = new float[DurationWindow * windowSize];//积分步长
    private volatile float[][] qQueue = new float[DurationWindow * windowSize][4];//姿态队列
    private volatile long[] timeStampQueue = new long[DurationWindow * windowSize];//积分时间
    private volatile int RemainingDataSize = 0;

    //Path 方向矢量
    private volatile float[] PathVector = new float[3];
    //Vehicle Attitude State
    private volatile float[][] EulerOnVehicle = new float[windowSize][2];
    private float[] attStateValues = {0, 0};
    //传感器参数

    private float AccRange;
    private float GyroRange;
    private float MagRange;
    private float RangeK = 0.8f;

    private boolean AccOriOriNew = false;
    private boolean GyroOriNew = false;
    private boolean MagOriNew = false;

    private long accTimestamp = 0;
    private long gyroTimestamp = 0;
    private long magTimestamp = 0;

    private float accDelt = 0;
    private float gyroDelt = 0;
    private float magDelt = 0;

    private volatile float[] rawacc = new float[3];
    private volatile float[] acc = new float[3];
    private volatile float[] gyro = new float[3];
    private volatile float[] mag = new float[3];

    private volatile float[] nrawacc = new float[3];
    private volatile float[] nacc = new float[3];
    private volatile float[] ngyro = new float[3];
    private volatile float[] nmag = new float[3];

    //姿态参数
    private volatile float[] androidDCM = new float[]{1.f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
    private volatile float[] androidQ = new float[]{1.f, 0f, 0f, 0f};
    private volatile float[] DCM_Static = new float[]{1.f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
    private volatile float[] Q_Static = new float[]{1.f, 0f, 0f, 0f};

    private volatile float[] Euler = {0f, 0f, 0f};
    private volatile float[] Quarternion = {1, 0f, 0f, 0f};

    //姿态滤波器
    private EKF ekf;
    private ekfParams ekfP;
    private ekfParamsHandle ekfPH;

    private GDF gdf;

    private FCF fcf;

    private GyroAtt gyroAtt;
    private GyroAtt gyroAttPath;

    //简单滤波器
    private LPF_I accLPF;
    private LPF_I gyroLPF;
    private LPF_I magLPF;

    private LPF_I accMouldingLPF;

    MeanFilter VehicleProMLP;

    MeanFilter phoneUseonVehicleProMLP;

    //加速度校准参数
    private static float[] params;

    //private float[] Spectrum = new float[FFT_SIZE / 2];
    //private float[] SpectrumID = new float[FFT_SIZE / 2];

    private float[] Spectrum = null;
    private float[] SpectrumID = null;
    private float maxFrequency;
    //线程参数
    private boolean threadDisable_data_update = false;

    //
    public TrackSensorListener(float accMaxRange, float gyroMaxRange, float magMaxRange, final boolean enableAttitude, final boolean enableVehicle, final boolean enablePath, final boolean enableAlwaysPath) {
        // TODO Auto-generated constructor stub
        super();
        ///store Task
        Log.d(TAG, "FFT SIZE\t" + FFT_SIZE);
        //传感器量程
        AccRange = accMaxRange;
        GyroRange = gyroMaxRange;
        MagRange = magMaxRange;

        updateStateThreshold();

        accLPF = new LPF_I();
        gyroLPF = new LPF_I();
        magLPF = new LPF_I();

        accMouldingLPF = new LPF_I();

        VehicleProMLP = new MeanFilter(10);
        phoneUseonVehicleProMLP = new MeanFilter(10);

        ekfPH = new ekfParamsHandle();
        ekfP = new ekfParams();
        ekf = new EKF();
        //ekf.AttitudeEKF_initialize();

        gdf = new GDF();

        fcf = new FCF();

        gyroAtt = new GyroAtt(androidQ);

        //加速度校准参数提取
        getAccCalibrateParams();

        if (enableAttitude) {
            AttitudeAndStateThread();
        }

        if (enableVehicle) {
            VehicleThread();
        }

        if (enablePath) {
            PathThread(enableAlwaysPath);
        }
    }

    private void PathThread(final boolean enableAlwaysPath) {
        if (!enableAlwaysPath) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    //等待Thread 1 填充数据
                    try {
                        Thread.sleep(DurationWindow * windowSize * sampleInterval);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    //初始化Thread 2 相关参数
                    int NOW_STATE = PhoneState.ABSOLUTE_STATIC_STATE;
                    int LAST_STATE = PhoneState.ABSOLUTE_STATIC_STATE;
                    int beginFlag = 0;
                    int stopFlag = 0;
                    int StopWindow = 0;
                    while (!threadDisable_data_update) {
                        try {
                            Thread.sleep(windowSize * sampleInterval);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        //更新状态阈值参数
                        updateStateThreshold();
                        //对最新数据窗口判断状态（数据队列最后一个窗口）
                        float[] naccSum = new float[windowSize];
                        for (int i = (DurationWindow - 1) * windowSize; i < DurationWindow * windowSize; i++) {
                            naccSum[i - (DurationWindow - 1) * windowSize] = naccQueue[i][0] * naccQueue[i][0] + naccQueue[i][1] * naccQueue[i][1];
                            //Log.d(TAG,"naccSum[i]\t"+String.valueOf(i)+"\t"+String.valueOf(naccSum[i - (DurationWindow - 1) * windowSize]));
                        }
                        float accSumMean = myMath.getMean(naccSum);
                        float accSumVar = myMath.getVar(naccSum);
                        NOW_STATE = stateRecognizeUseAccel(accSumMean, accSumVar);
                        //进入Path过程（动静切换）
                        if ((LAST_STATE == PhoneState.USER_STATIC_STATE || LAST_STATE == PhoneState.ABSOLUTE_STATIC_STATE) && NOW_STATE == PhoneState.UNKONW_STATE) {
                            //Log.d(TAG,"naccSum.length\t"+String.valueOf(naccSum.length));
                            //Log.d(TAG, "laccSumMean:" + String.valueOf(accSumMean));
                            //Log.d(TAG, "laccSumVar:" + String.valueOf(accSumVar));

                            LAST_STATE = NOW_STATE;
                            //定义path参数缓存
                            float[][] positionQ = new float[DurationWindow * windowSize][3];
                            float[][] velocityQueue = new float[DurationWindow * windowSize][3];
                            GLOBAL_NOW_STATE = NOW_STATE;
                            RemainingDataSize = (DurationWindow - 2) * windowSize;
                            //等待全部5秒数据到达
                            while (RemainingDataSize > 0) {
                                try {
                                    Thread.sleep(2);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }

                            //path数据填充完成
                            long[] timeWindow = timeStampQueue.clone();
                            float[][] gyroWindow = gyroQueue.clone();
                            float[] AccDeltTWindow = AccDeltTQueue.clone();
                            float[] GyroDeltTWindow = GyroDeltTQueue.clone();
                            float[][] accWindow = accQueue.clone();
                            float[][] naccWindow = naccQueue.clone();
                            float[][] magWindow = magQueue.clone();
                            float[][] qWindow = qQueue.clone();

                            int PhoneStateDuringPath = Window_STATE;

                            //申明首末窗口，大小为 2*windowSize
                            float[] accStartWindow = new float[2 * windowSize];//开始窗口
                            float[] accStopWindow = new float[2 * windowSize];//结束窗口


                            for (int i = 0; i < 2 * windowSize; i++) {//提取开始窗口数据
                                myMath.addData(accStartWindow, naccWindow[i][0] * naccWindow[i][0] + naccWindow[i][1] * naccWindow[i][1]);
                            }

                            //在开始窗口找出开始点
                            float[] accSlideWindow = new float[5];
                            for (int i = 0; i < 2 * windowSize - 5; i++) {
                                for (int j = 0; j < 5; j++) {
                                    accSlideWindow[j] = accStartWindow[i + j];
                                }
                                accSumMean = myMath.getMean(accSlideWindow);
                                accSumVar = myMath.getVar(accSlideWindow);

                                //Log.d(TAG, "startSlideWindowID\t" + i);
                                //Log.d(TAG, "laccSumMean:" + accSumMean);
                                //Log.d(TAG, "laccSumVar:" + accSumVar);

                                NOW_STATE = stateRecognizeUseAccel(accSumMean, accSumVar, GainStart);
                                if (NOW_STATE == PhoneState.UNKONW_STATE) {
                                    beginFlag = i;
                                    break;
                                }
                                beginFlag = 0;
                            }

                            //找出结束窗口，并提取结束窗口
                            for (int i = 2; i < DurationWindow; i++) {
                                naccSum = new float[windowSize];
                                for (int j = 0; j < windowSize; j++) {
                                    naccSum[j] = naccWindow[i * windowSize + j][0] * naccWindow[i * windowSize + j][0] + naccWindow[i * windowSize + j][1] * naccWindow[i * windowSize + j][1];

                                    myMath.addData(accStopWindow, naccSum[j]);//提取停止窗口

                                }

                                accSumMean = myMath.getMean(naccSum);
                                accSumVar = myMath.getVar(naccSum);

                                //Log.d(TAG, "windowID\t" + i);
                                //Log.d(TAG, "laccSumMean:" + accSumMean);
                                //Log.d(TAG, "laccSumVar:" + accSumVar);

                                NOW_STATE = stateRecognizeUseAccel(accSumMean, accSumVar);

                                if ((NOW_STATE == PhoneState.USER_STATIC_STATE || NOW_STATE == PhoneState.ABSOLUTE_STATIC_STATE) && LAST_STATE == PhoneState.UNKONW_STATE) {
                                    StopWindow = i;
                                    break;
                                }
                                LAST_STATE = NOW_STATE;
                                //运动时间超过5s
                                StopWindow = i;
                            }

                            //在结束窗口找出结束点
                            accSlideWindow = new float[5];
                            for (int i = 0; i < 2 * windowSize - 5; i++) {
                                for (int j = 0; j < 5; j++) {
                                    accSlideWindow[j] = accStopWindow[i + j];
                                }
                                accSumMean = myMath.getMean(accSlideWindow);
                                accSumVar = myMath.getVar(accSlideWindow);
                                //Log.d(TAG, "stopSlideWindowID\t" + i);
                                //Log.d(TAG, "laccSumMean:" + accSumMean);
                                //Log.d(TAG, "laccSumVar:" + accSumVar);

                                NOW_STATE = stateRecognizeUseAccel(accSumMean, accSumVar, GainStop);
                                if (NOW_STATE == PhoneState.USER_STATIC_STATE || NOW_STATE == PhoneState.ABSOLUTE_STATIC_STATE) {
                                    stopFlag = i;
                                    break;
                                }
                                stopFlag = i;
                            }

                            //Log.d(TAG, "beginFlag" + ":\t" + beginFlag);
                            //Log.d(TAG, "stopFlag:" + ":\t" + stopFlag);
                            //Log.d(TAG, "stopWindow:" + ":\t" + StopWindow);

                            //从0到beginFlag 为平稳静止，计算初始姿态
                            int InitialSize = windowSize;
                            if (beginFlag < 2 * windowSize && beginFlag > (1 / 5) * windowSize) {
                                InitialSize = beginFlag;
                            }
                            //初始姿态
                            for (int i = 0; i < InitialSize; i++) {
                                //Log.d(TAG, "acc Static\t" + String.valueOf(i) + "\t" + accWindow[i][0] + "\t" + accWindow[i][1] + "\t" + accWindow[i][2]);
                                //Log.d(TAG, "mag Static\t" + String.valueOf(i) + "\t" + magWindow[i][0] + "\t" + magWindow[i][1] + "\t" + magWindow[i][2]);
                            }
                            float[] _accOri = myMath.getMean(accWindow, 0, InitialSize);
                            float[] _magOri = myMath.getMean(magWindow, 0, InitialSize);
                            float[] _quarternion = myMath.getMean(qWindow, 0, InitialSize);
                            /*
                            for(int i = 0;i<InitialSize;i++){
                                _quarternion[i] = qWindow[i];
                                myLog.log(TAG,"_qStatic\t"+String.valueOf(i),_quarternion[i]);
                            }
                            */

                            //DCM_Static = androidAtt(_accOri, _magOri);
                            //Q_Static = myMath.Rot2Q(DCM_Static);
                            //myLog.log(TAG,"_qStatic\t",_quarternion);
                            gyroAttPath = new GyroAtt(_quarternion);

                            //myLog.log(TAG, "Q_Static:", Q_Static);
                            //初始加速度
                            float[] accNow = myMath.Q_coordinatesTransform(_quarternion, accWindow[beginFlag]);
                            float[] accLast = accNow.clone();
                            //Log.d(TAG, "accNow[0]:" + ":\t" + accNow[0]);
                            //Log.d(TAG, "accNow[1]:" + ":\t" + accNow[1]);
                            //Log.d(TAG, "accNow[2]:" + ":\t" + (accNow[2]));
                            //path输出数据缓存
                            StringBuffer pathOut = new StringBuffer();

                            //path插值数据提取
                            ArrayList<PathBasicData> Path = new ArrayList<PathBasicData>();
                            PathBasicData pathValue = new PathBasicData(accNow, 0f);
                            Path.add(pathValue);

                            float time0 = 0;
                            int PathLength = 1;

                            //LinearFit
                            ArrayList<float[]> fitData = new ArrayList<>();
                            //开始计算Path
                            pathOut.append(onVehicleProbability + "\n");
                            int PathStart = beginFlag + 1;
                            int PathStop = (StopWindow - 2) * windowSize + stopFlag;

                            if (PathStart < PathStop) {
                                for (int i = PathStart; i < PathStop; i++) {
                                    time0 += AccDeltTWindow[i];
                                    //when i = 0, velocitySample[i] =0; positionSample[i] =0;

                                    pathOut.append(timeWindow[i] + "\t");

                                    //角速度插值
                                    //float[] W = myMath.matrixDivide(myMath.matrixAdd(gyroWindow[i], gyroWindow[i - 1]), 2);
                                    float[] W = gyroWindow[i].clone();

                                    pathOut.append(W[0] + "\t");
                                    pathOut.append(W[1] + "\t");
                                    pathOut.append(W[2] + "\t");

                                    //Log.d(TAG, "accWindow[i][0]:" + String.valueOf(i) + ":\t" + accWindow[i][0]);
                                    //Log.d(TAG, "accWindow[i][1]:" + String.valueOf(i) + ":\t" + accWindow[i][1]);
                                    //Log.d(TAG, "accWindow[i][2]:" + String.valueOf(i) + ":\t" + accWindow[i][2]);

                                    pathOut.append(accWindow[i][0] + "\t");
                                    pathOut.append(accWindow[i][1] + "\t");
                                    pathOut.append(accWindow[i][2] + "\t");

                                    gyroAttPath.Filter(W, GyroDeltTWindow[i]);

                                    //myLog.log(TAG, "gyroAttPath q:", gyroAttPath.q);

                                    //float[] eu = gyroAttPath.Euler.clone();

                                    //myLog.log(TAG, "gyroAttPath Euler:", eu);

                                    accNow = myMath.Q_coordinatesTransform(gyroAttPath.q, accWindow[i]);

                                    //Log.d(TAG, "accNow[0]:" + String.valueOf(i) + ":\t" + accNow[0]);
                                    //Log.d(TAG, "accNow[1]:" + String.valueOf(i) + ":\t" + accNow[1]);
                                    //Log.d(TAG, "accNow[2]:" + String.valueOf(i) + ":\t" + (accNow[2] - myMath.G));

                                    //Log.d(TAG, "accLast[0]:" + String.valueOf(i) + ":\t" + accLast[0]);
                                    //Log.d(TAG, "accLast[1]:" + String.valueOf(i) + ":\t" + accLast[1]);
                                    //Log.d(TAG, "accLast[2]:" + String.valueOf(i) + ":\t" + (accLast[2] - myMath.G));

                                    pathOut.append((accNow[0] + accLast[0]) / 2 + "\t");
                                    pathOut.append((accNow[1] + accLast[1]) / 2 + "\t");
                                    pathOut.append((accNow[2] + accLast[2] - 2 * myMath.G) / 2 + "\t");

                                    //新速度
                                    velocityQueue[i][0] = velocityQueue[i - 1][0] + 0.5f * (accNow[0] + accLast[0]) * AccDeltTWindow[i];
                                    velocityQueue[i][1] = velocityQueue[i - 1][1] + 0.5f * (accNow[1] + accLast[1]) * AccDeltTWindow[i];
                                    velocityQueue[i][2] = velocityQueue[i - 1][2] + 0.5f * ((accNow[2] - myMath.G) + (accLast[2] - myMath.G)) * AccDeltTWindow[i];

                                    //记录上一次加速度
                                    accLast = accNow.clone();

                                    pathOut.append(velocityQueue[i][0] + "\t");
                                    pathOut.append(velocityQueue[i][1] + "\t");
                                    pathOut.append(velocityQueue[i][2] + "\t");

                                    //Log.d(TAG, "velocityQueue[0]:" + String.valueOf(i) + ":\t" + velocityQueue[i][0]);
                                    //Log.d(TAG, "velocityQueue[1]:" + String.valueOf(i) + ":\t" + velocityQueue[i][1]);
                                    //Log.d(TAG, "velocityQueue[2]:" + String.valueOf(i) + ":\t" + velocityQueue[i][2]);

                                    //新位置
                                    positionQ[i][0] = positionQ[i - 1][0] + 0.5f * (velocityQueue[i][0] + velocityQueue[i - 1][0]) * AccDeltTWindow[i];
                                    positionQ[i][1] = positionQ[i - 1][1] + 0.5f * (velocityQueue[i][1] + velocityQueue[i - 1][1]) * AccDeltTWindow[i];
                                    positionQ[i][2] = positionQ[i - 1][2] + 0.5f * (velocityQueue[i][2] + velocityQueue[i - 1][2]) * AccDeltTWindow[i]; //- freeFallPosition;

                                    pathOut.append(positionQ[i][0] + "\t");
                                    pathOut.append(positionQ[i][1] + "\t");
                                    pathOut.append(positionQ[i][2] + "\n");

                                    //Log.d(TAG, "position[0]" + String.valueOf(i) + ":\t" + positionQ[i][0]);
                                    //Log.d(TAG, "position[1]" + String.valueOf(i) + ":\t" + positionQ[i][1]);
                                    //Log.d(TAG, "position[2]" + String.valueOf(i) + ":\t" + positionQ[i][2]);

                                    pathValue = new PathBasicData(accNow, time0);
                                    Path.add(pathValue);

                                    float[] fitValue = {positionQ[i][0] * 100, positionQ[i][1] * 100};
                                    fitData.add(fitValue);

                                    PathLength++;
                                }

                                //输出path数据
                                pathOut.append("\n");
                                positionBuffer = pathOut;
                                Log.d(TAG, "PathLength\t" + PathLength);

                                positionQueue = positionQ.clone();

                                LinearFit linearFit = new LinearFit(fitData, 1, PhoneStateDuringPath);
                                linearFit.Fitting();

                                PathVector = linearFit.getUnitVector();//Path方向矢量

                                //Path插值(默认不执行)
                                if (ifInterpolation && PathLength > windowSize) {
                                    PathCal pathTest = new PathCal(Path, PathLength);

                                    pathTest.CalPath(getIfOnVehicleProbability());
                                    InterpositionBuffer = pathTest.getPathBuffer();
                                    InterpositionQueue = pathTest.getPathQueue();
                                    InterPosition = new float[DurationWindow * windowSize][3];
                                    for (int i = 0; i < InterpositionQueue.length; i++) {
                                        if (i % myMath.N == 0) {
                                            InterPosition[i / myMath.N] = InterpositionQueue[i];
                                        }
                                    }
                                } else {
                                    InterPosition = new float[DurationWindow * windowSize][3];
                                }

                                ifNewPath = true;
                            }
                        }//结束Path
                        LAST_STATE = NOW_STATE;
                    }
                }
            }).start();
        }
        //enableAlwaysPath = true
        else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    //等待Thread 1 填充数据

                    try {
                        Thread.sleep(DurationWindow * windowSize * sampleInterval);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    while (!threadDisable_data_update) {
                        try {
                            Thread.sleep(DurationWindow * windowSize * sampleInterval);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        float[][] gyroWindow = gyroQueue.clone();
                        float[] AccDeltTWindow = AccDeltTQueue.clone();
                        float[] GyroDeltTWindow = GyroDeltTQueue.clone();
                        float[][] accWindow = accQueue.clone();

                        int PhoneStateDuringPath = Window_STATE;

                        float[][] positionQ = new float[DurationWindow * windowSize][3];
                        float[][] velocityQueue = new float[DurationWindow * windowSize][3];

                        float[] _quarternion = qQueue[0].clone();
                        gyroAttPath = new GyroAtt(_quarternion);
                        float[] accNow = myMath.Q_coordinatesTransform(_quarternion, accWindow[0]);
                        float[] accLast = accNow.clone();

                        //LinearFit
                        ArrayList<float[]> fitData = new ArrayList<>();
                        //开始计算Path
                        for (int i = 1; i < accWindow.length; i++) {
                            //when i = 0, velocitySample[i] =0; positionSample[i] =0;

                            //角速度插值
                            float[] W = gyroWindow[i].clone();
                            gyroAttPath.Filter(W, GyroDeltTWindow[i]);

                            accNow = myMath.Q_coordinatesTransform(gyroAttPath.q, accWindow[i]);

                            //新速度
                            velocityQueue[i][0] = velocityQueue[i - 1][0] + 0.5f * (accNow[0] + accLast[0]) * AccDeltTWindow[i];
                            velocityQueue[i][1] = velocityQueue[i - 1][1] + 0.5f * (accNow[1] + accLast[1]) * AccDeltTWindow[i];
                            velocityQueue[i][2] = velocityQueue[i - 1][2] + 0.5f * ((accNow[2] - myMath.G) + (accLast[2] - myMath.G)) * AccDeltTWindow[i];

                            //记录上一次加速度
                            accLast = accNow.clone();

                            positionQ[i][0] = positionQ[i - 1][0] + 0.5f * (velocityQueue[i][0] + velocityQueue[i - 1][0]) * AccDeltTWindow[i];
                            positionQ[i][1] = positionQ[i - 1][1] + 0.5f * (velocityQueue[i][1] + velocityQueue[i - 1][1]) * AccDeltTWindow[i];
                            positionQ[i][2] = positionQ[i - 1][2] + 0.5f * (velocityQueue[i][2] + velocityQueue[i - 1][2]) * AccDeltTWindow[i]; //- freeFallPosition;

                            //Log.d(TAG, "position[0]" + String.valueOf(i) + ":\t" + positionQ[i][0]);
                            //Log.d(TAG, "position[1]" + String.valueOf(i) + ":\t" + positionQ[i][1]);
                            //Log.d(TAG, "position[2]" + String.valueOf(i) + ":\t" + positionQ[i][2]);
                            float[] fitValue = {positionQ[i][0] * 100, positionQ[i][1] * 100};
                            if (i % 5 == 0 && myMath.isLegalArray(fitValue)) {
                                fitData.add(fitValue);
                            }
                        }

                        positionQueue = positionQ.clone();
                        InterPosition = new float[DurationWindow * windowSize][3];
                        ifNewPath = true;
                        LinearFit linearFit = new LinearFit(fitData, 1, PhoneStateDuringPath);
                        linearFit.Fitting();

                        PathVector = linearFit.getUnitVector();//Path方向矢量
                    }
                }
            }).start();
        }
    }

    private void VehicleThread() {
        // Fre & ifVehicle Thread
        new Thread(new Runnable() {
            int ThreadLoopCount = 0;

            @Override
            public void run() {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                while (!threadDisable_data_update) {
                    try {
                        Thread.sleep(FFT_SampleInterval / 10);
                        ThreadLoopCount++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    updateStateThreshold();

                    if (onVehiclePhoneUseRecognize(EulerOnVehicle)) {
                        phoneUseonVehicleProbability = phoneUseonVehicleProMLP.filter(1.0f);
                    } else {
                        phoneUseonVehicleProbability = phoneUseonVehicleProMLP.filter(0.0f);
                    }
                    Log.d(TAG, "phoneUseOnVehicle\t" + phoneUseonVehicleProbability);

                    if(phoneUseonVehicleProbability>0.5){
                        phoneUseOnVehicle = true;
                    }else {
                        phoneUseOnVehicle = false;
                    }

                    if (ThreadLoopCount == 10) {
                        if (ifVehicle_STFT()) {
                            onVehicleProbability = VehicleProMLP.filter(1.0f);
                        } else {
                            onVehicleProbability = VehicleProMLP.filter(0.0f);
                        }

                        Log.d(TAG, "ifOnVehicle\t" + onVehicleProbability);

                        if (onVehicleProbability > VEHICLE_PROBABILITY_THRESHOLD) {
                            onVehicle = true;
                        } else {
                            onVehicle = false;
                        }
                        ThreadLoopCount = 0;
                    }
                }
            }
        }).start();

    }

    private boolean onVehiclePhoneUseRecognize(float[][] Euler) {
        float[] eulerSum = new float[windowSize];
        for (int i = 0; i < windowSize; i++) {
            eulerSum[i] = (float) Math.sqrt(Euler[i][0] * Euler[i][0] + Euler[i][1] * Euler[i][1]);
        }
        //float attStateMean = myMath.getMean(eulerSum);
        float attStateVar = myMath.getVar(eulerSum);
        attStateVar = (float) Math.log10(attStateVar);
        Log.d(TAG, "attStateVar\t" + attStateVar);
        if (attStateVar < PhoneState.PHONE_USE_ON_VEHICLE_EULER_THRESHOLD) {
            return false;
        } else {
            return true;
        }
    }

    private void AttitudeAndStateThread() {
        //Attitude & State & cal-data Thread
        new Thread(new Runnable() {///////////////////// Thread 1: Task to calculate attitude params
            @Override
            public void run() {
                int count = 0;
                long time = 0;
                long timeOld = System.nanoTime();
                float dt = sampleIntervalReal / 1000f;

                try {
                    Thread.sleep(windowSize * sampleInterval);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                while (!threadDisable_data_update) {
                    try {
                        Thread.sleep(sampleIntervalReal);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    updateStateThreshold();
                    //EKF
                    if (AccOriOriNew && MagOriNew && GyroOriNew) {
                        //myLog.log(TAG,"x_apo",ekf.x_apo);
                        //myLog.log(TAG,"P_apo",ekf.P_apo);
                        float[] accOri = acc.clone();
                        float[] gyroOri = gyro.clone();
                        float[] magOri = mag.clone();

                        if (AttitudeMode == PhoneState.Attitude_GDF) {
                            //用EKF初始化GDF
                            if (gdf.InitCount < 100) {
                                ekfAtt(accOri, gyroOri, magOri, dt);
                                Euler = ekf.euler.clone();
                                //DCM = ekf.Rot_matrix.clone();
                                Quarternion = ekf.q.clone();
                                gdf.InitCount++;
                                gdf.q = ekf.q.clone();
                            } else {
                                gdfAtt(accOri, gyroOri, magOri, dt, true);
                                Euler = gdf.Euler.clone();
                                //DCM = gdf.Rot_Matrix.clone();
                                Quarternion = gdf.q.clone();
                            }
                            //myLog.log(TAG, "gdf q:", Quarternion);
                        }
                        if (AttitudeMode == PhoneState.Attitude_EKF) {
                            ekfAtt(accOri, gyroOri, magOri, dt);
                            Euler = ekf.euler.clone();
                            //DCM = ekf.Rot_matrix.clone();
                            if (myMath.isLegalArray(ekf.q)) {
                                Quarternion = ekf.q.clone();
                            }
                            //myLog.log(TAG, "ekf q:", Quarternion);
                        }
                        if (AttitudeMode == PhoneState.Attitude_FCF) {
                            fcfAtt(accOri, gyroOri, magOri, dt);
                            Euler = fcf.euler.clone();
                            //DCM = fcf.Rot_matrix.clone();
                            Quarternion = fcf.q.clone();
                            //myLog.log(TAG, "fcf q:", Quarternion);
                        }

                        if (AttitudeMode == PhoneState.Attitude_ANDROID) {
                            androidAtt(accOri, magOri);
                            //DCM = androidDCM.clone();
                            Quarternion = androidQ.clone();
                            Euler = myMath.Q2Euler(Quarternion);
                        }

                        if (AttitudeMode == PhoneState.Attitude_GYRO) {
                            if (count++ == DurationWindow * windowSize) {
                                androidAtt(accOri, magOri);
                                //DCM = androidDCM.clone();
                                Quarternion = androidQ.clone();
                                Euler = myMath.Q2Euler(Quarternion);
                                gyroAtt = new GyroAtt(Quarternion);
                                count = 0;
                            } else {
                                gyroAtt.Filter(gyroOri, dt);
                                Quarternion = gyroAtt.q.clone();
                                Euler = myMath.Q2Euler(Quarternion);
                            }
                        }
                        Euler[2] -= myMath.DECLINATION / 180 * myMath.PI;//去除磁偏角
                    }
                    PhoneState.Euler = Euler.clone();
                    PhoneState.Quarternion = Quarternion.clone();

                    time = System.nanoTime();
                    dt = (time - timeOld) / 1000000000f;
                    timeOld = time;
                    //Log.d(TAG,"thread DT\t"+dt);

                    myMath.addData(timeStampQueue, System.currentTimeMillis());
                    myMath.addData(qQueue, Quarternion);

                    float accLow = myMath.getMoulding(acc) + myMath.G;
                    float accHigh = accLow - accMouldingLPF.filter(accLow);

                    myMath.addData(accNormQueue, accHigh);

                    float[] naccSum = new float[windowSize];
                    for (int i = (DurationWindow - 1) * windowSize; i < DurationWindow * windowSize; i++) {
                        naccSum[i - (DurationWindow - 1) * windowSize] = (naccQueue[i][0] * naccQueue[i][0] + naccQueue[i][1] * naccQueue[i][1]);
                    }
                    stateValues[0] = myMath.getMean(naccSum);
                    stateValues[1] = myMath.getVar(naccSum);

                    Window_STATE = stateRecognizeUseAccel(stateValues[0], stateValues[1]);


                    float[] EulerVehicle = {Euler[0], Euler[1]};
                    myMath.addData(EulerOnVehicle, EulerVehicle);
                }
            }
        }).start();

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // TODO Auto-generated method stub
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                if (event.values != null) {
                    if (myMath.getMoulding(event.values) < RangeK * AccRange) {
                        float[] _rawacc = event.values.clone();
                        rawacc = myMath.V_android2Ned(_rawacc);
                        float[] _acc = AccCalibrate(rawacc);
                        acc = _acc.clone();
                        accDelt = (event.timestamp - accTimestamp) / 1000000000f;
                        accTimestamp = event.timestamp;
                        //Log.d(TAG,"acc DT\t"+accDelt);
                        nacc = myMath.Q_coordinatesTransform(Quarternion, acc);
                        nrawacc = myMath.Q_coordinatesTransform(Quarternion, rawacc);
                        myMath.addData(accQueue, acc);
                        myMath.addData(naccQueue, nacc);
                        myMath.addData(AccDeltTQueue, accDelt);
                        //判断为Path时，记录数据是否填充完毕
                        if (GLOBAL_NOW_STATE == PhoneState.UNKONW_STATE && RemainingDataSize > 0) {
                            RemainingDataSize--;
                        }

                        AccOriOriNew = true;
                    }
                }
                break;
            case Sensor.TYPE_GYROSCOPE:
                if (event.values != null) {
                    if (myMath.getMoulding(event.values) < RangeK * GyroRange) {
                        float[] _gyro = event.values.clone();
                        gyro = myMath.V_android2Ned(_gyro);
                        gyroDelt = (event.timestamp - gyroTimestamp) / 1000000000f;
                        gyroTimestamp = event.timestamp;
                        //Log.d(TAG,"gyro DT\t"+gyroDelt);
                        ngyro = myMath.Q_coordinatesTransform(Quarternion, gyro);
                        myMath.addData(gyroQueue, gyro);
                        myMath.addData(GyroDeltTQueue, gyroDelt);
                        GyroOriNew = true;
                    }
                }
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                if (event.values != null) {
                    if (myMath.getMoulding(event.values) < RangeK * MagRange) {
                        float[] _mag = event.values.clone();
                        mag = myMath.V_android2Ned(_mag);
                        magDelt = (event.timestamp - magTimestamp) / 1000000000f;
                        magTimestamp = event.timestamp;
                        nmag = myMath.Q_coordinatesTransform(Quarternion, mag);
                        myMath.addData(magQueue, mag);
                        MagOriNew = true;
                    }
                }
                break;
        }
    }


    public void setAttitudeMode(int mode) {
        AttitudeMode = mode;
    }

    private void getAccCalibrateParams() {
        PhoneState.initAccCalibrateParams();
        params = PhoneState.getCalibrateParams();
    }

    private void updateStateThreshold() {
        ACC_MEAN_ABSOLUTE_STATIC_THRESHOLD = PhoneState.ACC_MEAN_ABSOLUTE_STATIC_THRESHOLD;
        ACC_MEAN_STATIC_THRESHOLD = PhoneState.ACC_MEAN_STATIC_THRESHOLD;
        ACC_VAR_ABSOLUTE_STATIC_THRESHOLD = PhoneState.ACC_VAR_ABSOLUTE_STATIC_THRESHOLD;
        ACC_VAR_STATIC_THRESHOLD = PhoneState.ACC_VAR_STATIC_THRESHOLD;

        AMPDB_THRESHOLD = PhoneState.AMPDB_THRESHOLD;
        PEAK_FRE_THRESHOLD = PhoneState.PEAK_FRE_THRESHOLD;
        VEHICLE_PROBABILITY_THRESHOLD = PhoneState.VEHICLE_PROBABILITY_THRESHOLD;
    }


    public float[] readRawAccData(int TYPE) {
        if (TYPE == 0) {
            return this.rawacc;
        } else return this.nrawacc;
    }

    public float[] readAccData(int TYPE) {
        if (TYPE == 0) {
            return this.acc;
        } else return this.nacc;
    }

    public float[] readGyroData(int TYPE) {

        if (TYPE == 0) {
            return this.gyro;
        } else return this.ngyro;
    }

    public float[] readMagData(int TYPE) {

        if (TYPE == 0) {
            return this.mag;
        } else return this.nmag;
    }

    public float[] readEuler() {
        return this.Euler;
    }

    public float[] readQ() {
        return this.Quarternion;
    }

    public float[][] getPosition() {
        return positionQueue;
    }

    public float[][] getInterPosition() {
        return InterPosition;
    }

    public StringBuffer getPositionString() {
        return positionBuffer;
    }

    public StringBuffer getInterPositionString() {
        return InterpositionBuffer;
    }

    public boolean ifNewPath() {
        return ifNewPath;
    }

    public int getNowState() {
        return Window_STATE;
    }

    public float[] getNowStateValues() {
        return stateValues;
    }

    public void closeSensorThread() {
        threadDisable_data_update = true;
    }


    private float[] AccCalibrate(float[] rData) {
        float[] data = new float[3];
        data[0] = rData[0] - params[9];
        data[1] = rData[1] - params[10];
        data[2] = rData[2] - params[11];
        //减去shift
        float[] aData = new float[3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                aData[i] += params[i * 3 + j] * data[j];
            }
        }
        return aData.clone();
    }

    private void ekfAtt(float[] accOri, float[] gyroOri, float[] magOri, float dt) {
        ekf.update_vect[0] = 1;
        ekf.update_vect[1] = 1;
        ekf.update_vect[2] = 1;

        ekf.z_k[0] = gyroOri[0];
        ekf.z_k[1] = gyroOri[1];
        ekf.z_k[2] = gyroOri[2];

        ekf.z_k[3] = accOri[0];
        ekf.z_k[4] = accOri[1];
        ekf.z_k[5] = accOri[2];

        ekf.z_k[6] = magOri[0] / 100.f;
        ekf.z_k[7] = magOri[1] / 100.f;
        ekf.z_k[8] = magOri[2] / 100.f;

        ekf.x_aposteriori_k[0] = ekf.z_k[0];
        ekf.x_aposteriori_k[1] = ekf.z_k[1];
        ekf.x_aposteriori_k[2] = ekf.z_k[2];
        ekf.x_aposteriori_k[3] = 0.0f;
        ekf.x_aposteriori_k[4] = 0.0f;
        ekf.x_aposteriori_k[5] = 0.0f;
        ekf.x_aposteriori_k[6] = ekf.z_k[3];
        ekf.x_aposteriori_k[7] = ekf.z_k[4];
        ekf.x_aposteriori_k[8] = ekf.z_k[5];
        ekf.x_aposteriori_k[9] = ekf.z_k[6];
        ekf.x_aposteriori_k[10] = ekf.z_k[7];
        ekf.x_aposteriori_k[11] = ekf.z_k[8];
        //Log.d(TAG, "calculateOrientation");
        ekfP.parameters_update(ekfPH);
        ekf.dt = dt;
        ekf.AttitudeEKF(false, // approx_prediction
                ekfP.use_moment_inertia, ekf.update_vect, ekf.dt, ekf.z_k, ekfP.q0, // q_rotSpeed,
                ekfP.q1, // q_rotAcc
                ekfP.q2, // q_acc
                ekfP.q3, // q_mag
                ekfP.r0, // r_gyro
                ekfP.r1, // r_accel
                ekfP.r2, // r_mag
                ekfP.moment_inertia_J, ekf.x_aposteriori, ekf.P_aposteriori, ekf.Rot_matrix, ekf.euler, ekf.debugOutput, ekf.euler_pre);
        //ekf.time = System.nanoTime();
        ekf.q = myMath.Rot2Q(ekf.Rot_matrix);
    }

    private void gdfAtt(float[] accOri, float[] gyroOri, float[] magOri, float dt, boolean gyroIMU) {
        gdf.Filter(gyroOri[0], -gyroOri[1], -gyroOri[2], accOri[0], -accOri[1], -accOri[2], magOri[0], -magOri[1], -magOri[2], dt, gyroIMU);
    }

    private float[] androidAtt(float[] accOri, float[] magOri) {
        float[] _accOri = myMath.V_android2Ned(accOri);
        float[] _magOri = myMath.V_android2Ned(magOri);

        float[] aDCM = new float[9];
        SensorManager.getRotationMatrix(aDCM, null, _accOri, _magOri);
        androidDCM = myMath.R_android2Ned(aDCM);
        androidQ = myMath.Rot2Q(androidDCM);
        return myMath.R_android2Ned(aDCM).clone();
    }

    private void fcfAtt(float[] accOri, float[] gyroOri, float[] magOri, float dt) {

        accOri[0] *= -1;
        accOri[2] *= -1;
        gyroOri[0] *= -1;
        gyroOri[2] *= -1;
        magOri[0] *= -1;
        magOri[2] *= -1;
        fcf.acc = accOri.clone();
        fcf.gyro = gyroOri.clone();
        fcf.mag = magOri.clone();
        fcf.dt = dt;
        fcf.attitude(dt);
    }

    private int stateRecognizeUseAccel(float laccSumMean, float laccSumVar, float gain) {
        if (laccSumMean < gain * ACC_MEAN_ABSOLUTE_STATIC_THRESHOLD && laccSumVar < gain * ACC_VAR_ABSOLUTE_STATIC_THRESHOLD) {//gyroSumMean < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumMean < ACC_ABSOLUTE_STATIC_THRESHOLD && gyroSumVar < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumVar < ACC_ABSOLUTE_STATIC_THRESHOLD) {
            return PhoneState.ABSOLUTE_STATIC_STATE;
        } else if (laccSumMean < gain * ACC_MEAN_STATIC_THRESHOLD && laccSumVar < gain * ACC_VAR_STATIC_THRESHOLD) {//laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {gyroSumMean < GYRO_STATIC_THRESHOLD && laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {
            return PhoneState.USER_STATIC_STATE;
        } else {
            return PhoneState.UNKONW_STATE;
        }
    }

    private int stateRecognizeUseAccel(float laccSumMean, float laccSumVar) {
        if (laccSumMean < ACC_MEAN_ABSOLUTE_STATIC_THRESHOLD && laccSumVar < ACC_VAR_ABSOLUTE_STATIC_THRESHOLD) {//gyroSumMean < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumMean < ACC_ABSOLUTE_STATIC_THRESHOLD && gyroSumVar < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumVar < ACC_ABSOLUTE_STATIC_THRESHOLD) {
            return PhoneState.ABSOLUTE_STATIC_STATE;
        } else if (laccSumMean < ACC_MEAN_STATIC_THRESHOLD && laccSumVar < ACC_VAR_STATIC_THRESHOLD) {//laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {gyroSumMean < GYRO_STATIC_THRESHOLD && laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {
            return PhoneState.USER_STATIC_STATE;
        } else {
            return PhoneState.UNKONW_STATE;
        }
    }
/*
    private int stateRecognizeUseAccelVar(float laccSumVar) {
        if (laccSumVar < ACC_VAR_ABSOLUTE_STATIC_THRESHOLD) {//gyroSumMean < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumMean < ACC_ABSOLUTE_STATIC_THRESHOLD && gyroSumVar < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumVar < ACC_ABSOLUTE_STATIC_THRESHOLD) {
            return PhoneState.ABSOLUTE_STATIC_STATE;
        } else if (laccSumVar < ACC_VAR_STATIC_THRESHOLD) {//laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {gyroSumMean < GYRO_STATIC_THRESHOLD && laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {
            return PhoneState.USER_STATIC_STATE;
        } else {
            return PhoneState.UNKONW_STATE;
        }
    }

    private int stateRecognizeUseAccelVar(float laccSumVar, float gain) {
        if (laccSumVar < gain * ACC_VAR_ABSOLUTE_STATIC_THRESHOLD) {//gyroSumMean < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumMean < ACC_ABSOLUTE_STATIC_THRESHOLD && gyroSumVar < GYRO_ABSOLUTE_STATIC_THRESHOLD && laccSumVar < ACC_ABSOLUTE_STATIC_THRESHOLD) {
            return PhoneState.ABSOLUTE_STATIC_STATE;
        } else if (laccSumVar < gain * ACC_VAR_STATIC_THRESHOLD) {//laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {gyroSumMean < GYRO_STATIC_THRESHOLD && laccSumMean < ACC_STATIC_THRESHOLD && gyroSumVar < GYRO_STATIC_THRESHOLD && laccSumVar < ACC_STATIC_THRESHOLD) {
            return PhoneState.USER_STATIC_STATE;
        } else {
            return PhoneState.UNKONW_STATE;
        }
    }
*/

    private boolean ifVehicle_STFT() {
        STFT stft = new STFT(FFT_SIZE/STFT_SECTIONS, 1000 / sampleInterval, 1, "Hanning");
        //wndName: Bartlett, Hanning, Blackman, Blackman Harris, Kaiser, a=2.0/3.0/4.0
        float[] input = accNormQueue.clone();
        stft.feedData(input);
        stft.getSpectrumAmp();
        double[] output = stft.getSpectrumAmpDB();
        stft.calculatePeak();
        maxFrequency = (float) stft.maxAmpFreq;
        Log.d(TAG, "Max Frequency\t" + maxFrequency);

        SpectrumID = new float[output.length];
        Spectrum = new float[output.length];
        Log.d(TAG," Spectrum Length\t"+output.length);
        for (int i = 0; i < output.length; i++) {
            Spectrum[i] = (float) output[i] / 10.f;
            SpectrumID[i] = (i * (0.5f * 50.f / (output.length - 1)));
            //myLog.log(TAG," SpectrumID\t",SpectrumID);
        }
        myLog.log(TAG, "FFT result\t", Spectrum);
        if (maxFrequency > PEAK_FRE_THRESHOLD) {
            return true;
        }
        if (stft.calculateMeanHighFreqzAmpDB(PEAK_FRE_THRESHOLD) > 0.8 * PhoneState.AMPDB_THRESHOLD) {
            return true;
        }
        return false;
    }

    public float getMaxFrequency() {
        return maxFrequency;
    }

    public float[] getSpectrum() {
        if (Spectrum != null) {
            return Spectrum;
        } else return null;
    }

    public float[] getSpectrumID() {
        if (Spectrum != null) {
            return SpectrumID;
        } else return null;
    }

    public boolean getIfOnVehicle() {
        return onVehicle;
    }

    public boolean getIfPhoneUseOnVehicle() {
        return phoneUseOnVehicle;
    }

    public float getIfOnVehicleProbability() {
        return onVehicleProbability;
    }

    public float[] getPathVector() {
        return PathVector;
    }

}
