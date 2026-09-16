package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class DirectedMedian extends Node {
    public DirectedMedian() {
        super("", "DirectedMedian");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        GLTexture grad;
        grad = basePipeline.main3;
        glUtils.ConvDiff(previousNode.workingTexture, grad, 0.0f);
        {
            glProg.setDefine("INTENSE", (float) basePipeline.mSettings.noiseRstr);
            glProg.setDefine("INSIZE", previousNode.workingTexture.mSize);
            glProg.useAssetProgram("DirectedMedian/directedmedian");
            glProg.setTexture("InputBuffer", previousNode.workingTexture);
            glProg.setTexture("GradBuffer", grad);
            workingTexture = basePipeline.getMain();
            glProg.drawBlocks(workingTexture);
        }
        glProg.closed = true;
    }
}
