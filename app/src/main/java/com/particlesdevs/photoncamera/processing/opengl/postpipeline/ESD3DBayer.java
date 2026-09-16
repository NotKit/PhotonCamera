package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class ESD3DBayer extends Node {

    public ESD3DBayer() {
        super("", "ES3D");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        startT();
        GLTexture map = glUtils.medianDown(previousNode.workingTexture,4);
        endT("MedianDown");
        startT();
        GLTexture grad;
        /*
        if(previousNode.WorkingTexture != basePipeline.main3){
            grad = basePipeline.main3;
            WorkingTexture = basePipeline.getMain();
        }
        else {
            grad = basePipeline.getMain();
            WorkingTexture = basePipeline.main3;
        }*/
        workingTexture = basePipeline.getMain();
        grad = basePipeline.main3;
        glProg.setDefine("INSIZE",basePipeline.workSize);
        glProg.useAssetProgram("ESD3DBayer/diffbayer");
        glProg.setTexture("InputBuffer", previousNode.workingTexture);
        glProg.drawBlocks(grad);
        endT("Differentiate");
        //glUtils.ConvDiff(previousNode.WorkingTexture, grad, 0.0f);



        startT();
        {
            Log.d(Name, "NoiseS:" + basePipeline.noiseS + ", NoiseO:" + basePipeline.noiseO);
            glProg.setDefine("NOISES", basePipeline.noiseS);
            glProg.setDefine("NOISEO", basePipeline.noiseO);
            glProg.setDefine("INSIZE", previousNode.workingTexture.mSize);
            glProg.useAssetProgram("esd3dbayer");
            glProg.setTexture("NoiseMap", map);
            glProg.setTexture("InputBuffer", previousNode.workingTexture);
            glProg.setTexture("GradBuffer", grad);
            glProg.drawBlocks(workingTexture);
        }
        endT("ES3D");
        glProg.closed = true;
        map.close();
    }
}
