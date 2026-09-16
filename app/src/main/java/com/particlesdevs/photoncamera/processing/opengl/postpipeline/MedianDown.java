package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class MedianDown extends Node {
    public MedianDown() {
        super("", "MedianDownUpscale");
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        workingTexture = basePipeline.getMain();
        glUtils.medianDown(previousNode.workingTexture,workingTexture,2);
        basePipeline.workSize = new Point(workingTexture.mSize.x/2,workingTexture.mSize.y/2);
        glProg.closed = true;
    }
}
