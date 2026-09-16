package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class HotPixelFilter extends Node {


    public HotPixelFilter() {
        super("", "HotPixelFilter");
    }

    @Override
    public void Compile() {}

    int tile = 8;
    @Override
    public void Run() {
        glProg.setLayout(tile,tile,1);
        int tileSize = 7;
        glProg.setDefine("OUTSET",previousNode.workingTexture.mSize);
        glProg.setDefine("TILE",tileSize);
        glProg.setDefine("NOISEO",basePipeline.noiseO);
        glProg.setDefine("NOISES",basePipeline.noiseS);
        glProg.setDefine("IMPULSE",5.0f);
        glProg.useAssetProgram("HotPixelFilter/hotpixels",true);
        glProg.setTextureCompute("inTexture",previousNode.workingTexture,false);
        workingTexture = previousNode.workingTexture;
        glProg.setTextureCompute("outTexture",workingTexture,true);
        for(int i =0; i<5;i++)
            glProg.computeManual(workingTexture.mSize.x/(8*tileSize),workingTexture.mSize.y/(8*tileSize),3);
        glProg.closed = true;
    }
}
