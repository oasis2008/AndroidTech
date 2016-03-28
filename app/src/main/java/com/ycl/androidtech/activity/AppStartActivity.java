package com.ycl.androidtech.activity;


import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;

import com.ycl.androidtech.R;
import com.ycl.androidtech.statistics.TimeMonitorConfig;
import com.ycl.androidtech.statistics.TimeMonitorManager;


public class AppStartActivity extends Activity {

    ImageView mLogo = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        TimeMonitorManager.getInstance().getTimeMonitor(TimeMonitorConfig.TIME_MONITOR_ID_APPLICATION_START).recodingTimeTag("AppStartActivity_create");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_app_start);
        mLogo = (ImageView) this.findViewById(R.id.logo);
        //mStartHandler.sendEmptyMessageDelayed(0,1000);
       // useAnimation();
        useAnimator();
        TimeMonitorManager.getInstance().getTimeMonitor(TimeMonitorConfig.TIME_MONITOR_ID_APPLICATION_START).recodingTimeTag("AppStartActivity_createOver");
    }

    @Override
    protected void onStart() {
        super.onStart();
        TimeMonitorManager.getInstance().getTimeMonitor(TimeMonitorConfig.TIME_MONITOR_ID_APPLICATION_START).end("AppStartActivity_start",false);
    }

    /*
        ���䶯��
        AlphaAnimation����͸���ȶ���Ч��
        ScaleAnimation����ߴ���������Ч��
        TranslateAnimation����ת��λ���ƶ�����Ч��
        RotateAnimation����ת����ת����Ч��
        * */
    private void useAnimation() {
      Animation mRota = new RotateAnimation(0, 360);
        ScaleAnimation mScale = new ScaleAnimation(0.0f, 1f, 0.0f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        mScale.setDuration(3000);
        mScale.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mStartHandler.sendEmptyMessageDelayed(0,0);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        mScale.setDuration(3000);
        mLogo.startAnimation(mScale);

    }
    //���Զ���
    private void useAnimator(){
        ObjectAnimator scalex = ObjectAnimator.ofFloat(mLogo, "scaleX", 0,1);
        ObjectAnimator scaley = ObjectAnimator.ofFloat(mLogo, "scaleY", 0, 1);
        ObjectAnimator rotation = ObjectAnimator.ofFloat(mLogo, "rotation", 0.0f, 360f);
        rotation.addListener(new ObjectAnimator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mStartHandler.sendEmptyMessageDelayed(0,0);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        AnimatorSet mSetPlayer = new AnimatorSet();
        mSetPlayer.setDuration(3000);
        mSetPlayer.play(scalex).with(scaley).with(rotation);
        mSetPlayer.start();
    }
    private Handler mStartHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            //test
            Intent it = new Intent(AppStartActivity.this, HomePageActivity.class);
            startActivity(it);
            finish();
        }
    };
}