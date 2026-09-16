package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class DemosaicCompute extends Node {
    public DemosaicCompute() {
        super("", "DemosaicCompute");
    }

    @Override
    public void Compile() {}
    float gradSize = 1.5f;
    float fuseMin = 0.0f;
    float fuseMax = 1.0f;
    float fuseShift = -0.5f;
    float fuseMpy = 6.0f;
    int tile = 16;
    @Override
    public void Run() {
        gradSize = getTuning("GradSize",gradSize);
        fuseMin = getTuning("FuseMin",fuseMin);
        fuseMax = getTuning("FuseMax",fuseMax);
        fuseShift = getTuning("FuseShift",fuseShift);
        fuseMpy = getTuning("FuseMpy",fuseMpy);
        GLTexture glTexture;
        glTexture = previousNode.workingTexture;
        //Gradients
        glProg.setLayout(tile,tile,1);
        glProg.setDefine("OUTSET",basePipeline.getMain3().mSize);
        glProg.useAssetProgram("demosaicp0c",true);
        glProg.setTextureCompute("inTexture", glTexture,false);
        glProg.setTextureCompute("outTexture", basePipeline.getMain3(),true);
        glProg.computeAuto(basePipeline.getMain3().mSize,1);
        GLTexture outp;

        //Green channel
        glProg.setDefine("GRADSIZE",gradSize);
        glProg.setDefine("SCANGRAD",15);
        glProg.setDefine("FUSEMIN",fuseMin);
        glProg.setDefine("FUSEMAX",fuseMax);
        glProg.setDefine("FUSESHIFT",fuseShift);
        glProg.setDefine("FUSEMPY",fuseMpy);
        glProg.setDefine("NOISES",basePipeline.noiseS);
        glProg.setDefine("NOISEO",basePipeline.noiseO);
        glProg.setLayout(tile,tile,1);
        glProg.setDefine("OUTSET",basePipeline.getMain3().mSize);
        glProg.useAssetProgram("demosaicp12c",true);
        glProg.setTextureCompute("inTexture",previousNode.workingTexture,false);
        glProg.setTextureCompute("gradTexture",basePipeline.getMain3(),false);

        if(basePipeline.mSettings.cfaPattern == -2) glProg.setDefine("QUAD","1");
        GLTexture prev = previousNode.workingTexture;
        outp = basePipeline.main1;
        if(basePipeline.main1 == previousNode.workingTexture){
            outp = basePipeline.main2;
        }
        glProg.setTextureCompute("outTexture",outp,true);
        glProg.computeAuto(outp.mSize,1);

        //Colour channels
        glProg.setLayout(tile,tile,1);
        glProg.setDefine("OUTSET",basePipeline.getMain3().mSize);
        glProg.useAssetProgram("demosaicp2c",true);
        glProg.setTextureCompute("inTexture", glTexture,false);
        glProg.setTextureCompute("greenTexture", outp,false);
        workingTexture = basePipeline.getMain3();
        glProg.setTextureCompute("outTexture",workingTexture,true);
        glProg.computeAuto(workingTexture.mSize,1);
        glProg.closed = true;
    }
}
