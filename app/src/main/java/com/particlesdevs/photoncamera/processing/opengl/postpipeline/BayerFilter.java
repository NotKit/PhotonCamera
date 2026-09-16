package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class BayerFilter extends Node {


    public BayerFilter() {
        super("", "BayerFilter");
    }

    @Override
    public void Compile() {}

    int tile = 8;
    @Override
    public void Run() {

        glProg.setLayout(tile,tile,1);
        glProg.setDefine("OUTSET",previousNode.workingTexture.mSize);
        glProg.setDefine("TILE",tile);
        glProg.setDefine("NOISEO",basePipeline.noiseO);
        glProg.setDefine("NOISES",basePipeline.noiseS);
        float ks = 1.0f + Math.min((basePipeline.noiseS+basePipeline.noiseO) * 1.0f * 100000.0f, 34.0f);
        int ksInt = (int)Math.min(ks,20.0f);
        int msize = 5 + ksInt - ksInt%2;
        Log.d("ESD3D", "KernelSize: "+ks+" MSIZE: "+msize);
        glProg.setDefine("KERNELSIZE", ks);
        glProg.setDefine("MSIZE", msize);
        glProg.useAssetProgram("esd3d2bayer",true);
        glProg.setTextureCompute("inTexture",previousNode.workingTexture,false);
        workingTexture = basePipeline.getMain();
        glProg.setTextureCompute("outTexture",workingTexture,true);
        //for(int i =0; i<5;i++)
        glProg.computeAuto(workingTexture.mSize,1);

        glProg.closed = true;
    }
}
