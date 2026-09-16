package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class MedianDownRevert extends Node {
    public MedianDownRevert() {
        super("", "MedianDownUpscale");
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        workingTexture = basePipeline.getMain();
        glUtils.interpolate(previousNode.workingTexture,workingTexture,2.0,workingTexture.mSize);
        basePipeline.workSize = new Point(workingTexture.mSize.x,workingTexture.mSize.y);
        glProg.closed = true;
    }
}
