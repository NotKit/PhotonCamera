package com.particlesdevs.photoncamera.util;

public class Math2 {
    public static double mix(double in, double in2, double t){
        return in*(1.-t)+in2*(t);
    }
    public static float mix(float in, float in2, float t){
        return in*(1.0f-t)+in2*(t);
    }
    public static int MirrorCoords(int i, int max){
        if(i < 0) return -i;
        else
        if(i>max-1)
            return max*2-i-1;
        return i;
    }
    public static float pdf(float x,float sigma){
        return (float) (0.39894* Math.exp(-0.5*x*x/(sigma*sigma))/sigma);
    }
    public static float smoothstep(float edge0, float edge1, float value) {
        float x = value;
        // Scale, bias and saturate x to 0..1 range
        x = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        // Evaluate polynomial
        return x * x * (3 - 2 * x);
    }
    public static float[] buildCumulativeHist(int[] hist, int outSize) {
        float[] cumulativeHist = new float[hist.length + 1];
        for (int i = 1; i < cumulativeHist.length; i++) {
            cumulativeHist[i] = cumulativeHist[i - 1] + hist[i - 1];
        }
        float max = cumulativeHist[hist.length];
        for (int i = 0; i < cumulativeHist.length; i++) {
            cumulativeHist[i] /= max;
        }
        float[] prevH = cumulativeHist.clone();
        float[] resampled = new float[outSize];
        for(int i =0; i<resampled.length;i++){
            resampled[i] = getInterpolated(prevH,i*((float)prevH.length/(resampled.length)));
        }
        return resampled;
    }
    public static float[] buildCumulativeHistInv(int[] hist, int outSize) {
        float[] cumulativeHist = new float[hist.length + 1];
        for (int i = 1; i < cumulativeHist.length; i++) {
            cumulativeHist[i] = cumulativeHist[i - 1] + hist[hist.length - (i - 1) - 1];
        }
        float max = cumulativeHist[hist.length];
        for (int i = 0; i < cumulativeHist.length; i++) {
            cumulativeHist[i] /= max;
        }
        float[] prevH = cumulativeHist.clone();
        float[] resampled = new float[outSize];
        for(int i =0; i<resampled.length;i++){
            resampled[i] = getInterpolated(prevH,i*((float)prevH.length/(resampled.length)));
        }
        return resampled;
    }
    private static float getInterpolated(float[] in, float ind){
        int indi = (int)ind;
        if(ind > indi){
            return mix(in[indi],in[Math.min(indi+1,in.length-1)],ind-indi);
        } else if(ind < indi){
            return mix(in[indi],in[Math.max(indi-1,0)],indi-ind);
        } else return in[indi];
    }

    public static float clamp(float value, float lowerlimit, float upperlimit) {
        float x = value;
        if (x < lowerlimit)
            x = lowerlimit;
        if (x > upperlimit)
            x = upperlimit;
        return x;
    }
    public static double clamp(double value, double lowerlimit, double upperlimit) {
        double x = value;
        if (x < lowerlimit)
            x = lowerlimit;
        if (x > upperlimit)
            x = upperlimit;
        return x;
    }
}
