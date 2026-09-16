package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class ImpulsePixelFilter extends Node {


    public ImpulsePixelFilter() {
        super("", "HotPixelFilter");
    }

    @Override
    public void Compile() {}
    void fixImpulse(String color){
        glProg.setLayout(tile,tile,1);
        int tileSize = 7;
        glProg.setDefine("OUTSET",previousNode.workingTexture.mSize);
        glProg.setDefine("TILE",tileSize);
        glProg.setDefine("NOISEO",basePipeline.noiseO);
        glProg.setDefine("NOISES",basePipeline.noiseS);
        glProg.setDefine("IMPULSE",8.0f);
        glProg.setDefine("COLOR",color);
        glProg.useAssetProgram("ImpulsePixelFilter/impixels",true);
        glProg.setTextureCompute("inTexture",previousNode.workingTexture,false);
        workingTexture = previousNode.workingTexture;
        glProg.setTextureCompute("outTexture",workingTexture,true);
        for(int i =0; i<3;i++)
            glProg.computeManual(workingTexture.mSize.x/(8*tileSize),workingTexture.mSize.y/(8*tileSize),3);
    }
    int tile = 16;
    @Override
    public void Run() {
        fixImpulse("rgb");
        glProg.closed = true;
    }
}
